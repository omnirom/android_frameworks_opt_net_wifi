/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wifi;


import android.annotation.NonNull;
import android.content.Context;
import android.hardware.wifi.hostapd.V1_0.HostapdStatus;
import android.hardware.wifi.hostapd.V1_0.HostapdStatusCode;
import android.hardware.wifi.hostapd.V1_0.IHostapd;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.wifi.WifiConfiguration;
import android.os.HwRemoteBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiNative.HostapdDeathEventHandler;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.WifiNative.SoftApListener;

import javax.annotation.concurrent.ThreadSafe;

import vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendor;
import vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendorIfaceCallback;

/**
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
@ThreadSafe
public class HostapdHal {
    private static final String TAG = "HostapdHal";

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;
    private final boolean mEnableAcs;
    private final boolean mEnableIeee80211AC;
    private String mCountryCode = null;

    // Hostapd HAL interface objects
    private IServiceManager mIServiceManager = null;
    private IHostapd mIHostapd;
    private IHostapdVendor mIHostapdVendor;
    private HostapdDeathEventHandler mDeathEventHandler;

    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
        public void onRegistration(String fqName, String name, boolean preexisting) {
            synchronized (mLock) {
                if (mVerboseLoggingEnabled) {
                    Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                            + ", " + name + " preexisting=" + preexisting);
                }
                if (!initHostapdService()) {
                    Log.e(TAG, "initalizing IHostapd failed.");
                    hostapdServiceDiedHandler();
                } else {
                    Log.i(TAG, "Completed initialization of IHostapd.");
                }
            }
        }
    };
    private final HwRemoteBinder.DeathRecipient mServiceManagerDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "IServiceManager died: cookie=" + cookie);
                    hostapdServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                }
            };
    private final HwRemoteBinder.DeathRecipient mHostapdDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "IHostapd/IHostapd died: cookie=" + cookie);
                    hostapdServiceDiedHandler();
                }
            };
    private final HwRemoteBinder.DeathRecipient mHostapdVendorDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "IHostapdVendor died: cookie=" + cookie);
                    hostapdServiceDiedHandler();
                }
            };


    public HostapdHal(Context context) {
        mEnableAcs = context.getResources().getBoolean(R.bool.config_wifi_softap_acs_supported);
        mEnableIeee80211AC =
                context.getResources().getBoolean(R.bool.config_wifi_softap_ieee80211ac_supported);
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param enable true to enable, false to disable.
     */
    void enableVerboseLogging(boolean enable) {
        synchronized (mLock) {
            mVerboseLoggingEnabled = enable;
        }
    }

    /**
     * Link to death for IServiceManager object.
     * @return true on success, false otherwise.
     */
    private boolean linkToServiceManagerDeath() {
        synchronized (mLock) {
            if (mIServiceManager == null) return false;
            try {
                if (!mIServiceManager.linkToDeath(mServiceManagerDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    hostapdServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IServiceManager.linkToDeath exception", e);
                mIServiceManager = null; // Will need to register a new ServiceNotification
                return false;
            }
            return true;
        }
    }

    /**
     * Registers a service notification for the IHostapd service, which triggers intialization of
     * the IHostapd and IHostapdVendor
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Registering IHostapd service ready callback.");
            }
            mIHostapd = null;
            mIHostapdVendor = null;
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
            try {
                mIServiceManager = getServiceManagerMockable();
                if (mIServiceManager == null) {
                    Log.e(TAG, "Failed to get HIDL Service Manager");
                    return false;
                }
                if (!linkToServiceManagerDeath()) {
                    return false;
                }
                /* TODO(b/33639391) : Use the new IHostapd.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(
                        IHostapd.kInterfaceName, "default", mServiceNotificationCallback)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + IHostapd.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for IHostapd service: "
                        + e);
                hostapdServiceDiedHandler();
                mIServiceManager = null; // Will need to register a new ServiceNotification
                return false;
            }
            return true;
        }
    }

    /**
     * Link to death for IHostapd object.
     * @return true on success, false otherwise.
     */
    private boolean linkToHostapdDeath() {
        synchronized (mLock) {
            if (mIHostapd == null) return false;
            try {
                if (!mIHostapd.linkToDeath(mHostapdDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IHostapd");
                    hostapdServiceDiedHandler();
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IHostapd.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    /**
     * Initialize the IHostapd object.
     * @return true on success, false otherwise.
     */
    private boolean initHostapdService() {
        synchronized (mLock) {
            try {
                mIHostapd = getHostapdMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "IHostapd.getService exception: " + e);
                return false;
            }
            if (mIHostapd == null) {
                Log.e(TAG, "Got null IHostapd service. Stopping hostapd HIDL startup");
                return false;
            }
            if (!linkToHostapdDeath()) {
                return false;
            }
        }

        if (!initHostapdVendorService())
            Log.e(TAG, "Failed to init HostapdVendor service");

        return true;
    }

    /**
     * Link to death for IHostapdVendor object.
     * @return true on success, false otherwise.
     */
    private boolean linkToHostapdVendorDeath() {
        synchronized (mLock) {
            if (mIHostapdVendor == null) return false;
            try {
                if (!mIHostapdVendor.linkToDeath(mHostapdVendorDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IHostapdVendor");
                    hostapdServiceDiedHandler();
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IHostapdVendor.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    /**
     * Initialize the IHostapdVendor object.
     * @return true on success, false otherwise.
     */
    public boolean initHostapdVendorService() {
        synchronized (mLock) {
            try {
                mIHostapdVendor = getHostapdVendorMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "IHostapdVendor.getService exception: " + e);
                return false;
            }
            if (mIHostapdVendor == null) {
                Log.e(TAG, "Got null IHostapdVendor service. Stopping hostapdVendor HIDL startup");
                return false;
            }
            if (!linkToHostapdVendorDeath()) {
                return false;
            }
        }
        return true;
    }


    /**
     * Add and start a new access point.
     *
     * @param ifaceName Name of the interface.
     * @param config Configuration to use for the AP.
     * @return true on success, false otherwise.
     */
    public boolean addAccessPoint(@NonNull String ifaceName, @NonNull WifiConfiguration config) {
        synchronized (mLock) {
            final String methodStr = "addAccessPoint";
            IHostapd.IfaceParams ifaceParams = new IHostapd.IfaceParams();
            ifaceParams.ifaceName = ifaceName;
            ifaceParams.hwModeParams.enable80211N = true;
            ifaceParams.hwModeParams.enable80211AC = mEnableIeee80211AC;
            try {
                ifaceParams.channelParams.band = getBand(config);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Unrecognized apBand " + config.apBand);
                return false;
            }
            if (mEnableAcs) {
                ifaceParams.channelParams.enableAcs = true;
                ifaceParams.channelParams.acsShouldExcludeDfs = true;
            } else {
                // Downgrade IHostapd.Band.BAND_ANY to IHostapd.Band.BAND_2_4_GHZ if ACS
                // is not supported.
                // We should remove this workaround once channel selection is moved from
                // ApConfigUtil to here.
                if (ifaceParams.channelParams.band == IHostapd.Band.BAND_ANY) {
                    Log.d(TAG, "ACS is not supported on this device, using 2.4 GHz band.");
                    ifaceParams.channelParams.band = IHostapd.Band.BAND_2_4_GHZ;
                }
                ifaceParams.channelParams.enableAcs = false;
                ifaceParams.channelParams.channel = config.apChannel;
            }

            IHostapd.NetworkParams nwParams = new IHostapd.NetworkParams();
            // TODO(b/67745880) Note that config.SSID is intended to be either a
            // hex string or "double quoted".
            // However, it seems that whatever is handing us these configurations does not obey
            // this convention.
            nwParams.ssid.addAll(NativeUtil.stringToByteArrayList(config.SSID));
            nwParams.isHidden = config.hiddenSSID;
            nwParams.encryptionType = getEncryptionType(config);
            nwParams.pskPassphrase = (config.preSharedKey != null) ? config.preSharedKey : "";
            if (!checkHostapdAndLogFailure(methodStr)) return false;
            try {
                HostapdStatus status = mIHostapd.addAccessPoint(ifaceParams, nwParams);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Remove a previously started access point.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    public boolean removeAccessPoint(@NonNull String ifaceName) {
        synchronized (mLock) {
            final String methodStr = "removeAccessPoint";
            if (!checkHostapdAndLogFailure(methodStr)) return false;
            try {
                HostapdStatus status = mIHostapd.removeAccessPoint(ifaceName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Add and start a new vendor access point.
     *
     * @param ifaceName Name of the softap interface.
     * @param config Configuration to use for the AP.
     * @param listener Callback for AP events.
     * @return true on success, false otherwise.
     */
    public boolean addVendorAccessPoint(@NonNull String ifaceName, @NonNull WifiConfiguration config, SoftApListener listener) {
        synchronized (mLock) {
            final String methodStr = "addVendorAccessPoint";
            WifiApConfigStore apConfigStore = WifiInjector.getInstance().getWifiApConfigStore();
            IHostapdVendor.VendorIfaceParams vendorIfaceParams = new IHostapdVendor.VendorIfaceParams();
            IHostapd.IfaceParams ifaceParams = vendorIfaceParams.ifaceParams;
            ifaceParams.ifaceName = ifaceName;
            ifaceParams.hwModeParams.enable80211N = true;
            ifaceParams.hwModeParams.enable80211AC = mEnableIeee80211AC;
            vendorIfaceParams.countryCode = (mCountryCode == null) ? "" : mCountryCode;
            vendorIfaceParams.bridgeIfaceName = "";
            try {
                ifaceParams.channelParams.band = getBand(config);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Unrecognized apBand " + config.apBand);
                return false;
            }
            if (mEnableAcs) {
                ifaceParams.channelParams.enableAcs = true;
                ifaceParams.channelParams.acsShouldExcludeDfs = true;
            } else {
                // Downgrade IHostapd.Band.BAND_ANY to IHostapd.Band.BAND_2_4_GHZ if ACS
                // is not supported.
                // We should remove this workaround once channel selection is moved from
                // ApConfigUtil to here.
                if (ifaceParams.channelParams.band == IHostapd.Band.BAND_ANY) {
                    Log.d(TAG, "ACS is not supported on this device, using 2.4 GHz band.");
                    ifaceParams.channelParams.band = IHostapd.Band.BAND_2_4_GHZ;
                }
                ifaceParams.channelParams.enableAcs = false;
                ifaceParams.channelParams.channel = config.apChannel;
            }

            IHostapd.NetworkParams nwParams = new IHostapd.NetworkParams();
            nwParams.ssid.addAll(NativeUtil.stringToByteArrayList(config.SSID));
            nwParams.isHidden = config.hiddenSSID;
            nwParams.encryptionType = getEncryptionType(config);
            nwParams.pskPassphrase = (config.preSharedKey != null) ? config.preSharedKey : "";

            if (!checkHostapdVendorAndLogFailure(methodStr)) return false;
            try {
                if (apConfigStore.getDualSapStatus()) {
                    String bridgeIfaceName = apConfigStore.getBridgeInterface();
                    vendorIfaceParams.bridgeIfaceName = (bridgeIfaceName != null) ? bridgeIfaceName : "";
                }

                HostapdStatus status = mIHostapdVendor.addVendorAccessPoint(vendorIfaceParams, nwParams);
                if (checkVendorStatusAndLogFailure(status, methodStr) && (mIHostapdVendor != null)) {
                    IHostapdVendorIfaceCallback vendorcallback = new HostapdVendorIfaceHalCallback(ifaceName, listener);
                    if (vendorcallback != null) {
                        if (!registerVendorCallback(ifaceParams.ifaceName, mIHostapdVendor, vendorcallback)) {
                            Log.e(TAG, "Failed to register Hostapd Vendor callback");
                            return false;
                        }
                    } else {
                        Log.e(TAG, "Failed to create vendorcallback instance");
                    }
                    return true;
                }
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
            return false;
        }
    }

    /**
     * Remove a previously started access point.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    public boolean removeVendorAccessPoint(@NonNull String ifaceName) {
        synchronized (mLock) {
            final String methodStr = "removeVendorAccessPoint";
            WifiApConfigStore apConfigStore = WifiInjector.getInstance().getWifiApConfigStore();

            if (!checkHostapdVendorAndLogFailure(methodStr)) return false;
            try {
                HostapdStatus status = mIHostapdVendor.removeVendorAccessPoint(ifaceName);
                return checkVendorStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }


    /**
     * Set hostapd parameters via QSAP command.
     *
     * This would call QSAP library APIs via hostapd hidl.
     *
     * @param cmd QSAP command.
     * @return true on success, false otherwise.
     */
    public boolean setHostapdParams(@NonNull String cmd) {
        synchronized (mLock) {
            final String methodStr = "setHostapdParams";
            if (!checkHostapdVendorAndLogFailure(methodStr)) return false;
            try {
                HostapdStatus status = mIHostapdVendor.setHostapdParams(cmd);
                return checkVendorStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }


    /**
     * Registers a death notification for hostapd.
     * @return Returns true on success.
     */
    public boolean registerDeathHandler(@NonNull HostapdDeathEventHandler handler) {
        if (mDeathEventHandler != null) {
            Log.e(TAG, "Death handler already present");
        }
        mDeathEventHandler = handler;
        return true;
    }

    /**
     * Deregisters a death notification for hostapd.
     * @return Returns true on success.
     */
    public boolean deregisterDeathHandler() {
        if (mDeathEventHandler == null) {
            Log.e(TAG, "No Death handler present");
        }
        mDeathEventHandler = null;
        return true;
    }

    /**
     * Clear internal state.
     */
    private void clearState() {
        synchronized (mLock) {
            mIHostapd = null;
            mIHostapdVendor = null;
        }
    }

    /**
     * Handle hostapd death.
     */
    private void hostapdServiceDiedHandler() {
        synchronized (mLock) {
            clearState();
            if (mDeathEventHandler != null) {
                mDeathEventHandler.onDeath();
            }
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationStarted() {
        synchronized (mLock) {
            return mIServiceManager != null;
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationComplete() {
        synchronized (mLock) {
            return mIHostapd != null;
        }
    }

    /**
     * Terminate the hostapd daemon.
     */
    public void terminate() {
        synchronized (mLock) {
            final String methodStr = "terminate";
            if (!checkHostapdAndLogFailure(methodStr)) return;
            try {
                mIHostapd.terminate();
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
        }
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    @VisibleForTesting
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        synchronized (mLock) {
            return IServiceManager.getService();
        }
    }

    @VisibleForTesting
    protected IHostapd getHostapdMockable() throws RemoteException {
        synchronized (mLock) {
            return IHostapd.getService();
        }
    }

    @VisibleForTesting
    protected IHostapdVendor getHostapdVendorMockable() throws RemoteException {
        synchronized (mLock) {
            return IHostapdVendor.getService();
        }
    }

    private static int getEncryptionType(WifiConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getAuthType()) {
            case WifiConfiguration.KeyMgmt.NONE:
                encryptionType = IHostapd.EncryptionType.NONE;
                break;
            case WifiConfiguration.KeyMgmt.WPA_PSK:
                encryptionType = IHostapd.EncryptionType.WPA;
                break;
            case WifiConfiguration.KeyMgmt.WPA2_PSK:
                encryptionType = IHostapd.EncryptionType.WPA2;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType = IHostapd.EncryptionType.NONE;
                break;
        }
        return encryptionType;
    }

    private static int getBand(WifiConfiguration localConfig) {
        int bandType;
        switch (localConfig.apBand) {
            case WifiConfiguration.AP_BAND_2GHZ:
                bandType = IHostapd.Band.BAND_2_4_GHZ;
                break;
            case WifiConfiguration.AP_BAND_5GHZ:
                bandType = IHostapd.Band.BAND_5_GHZ;
                break;
            case WifiConfiguration.AP_BAND_ANY:
                bandType = IHostapd.Band.BAND_ANY;
                break;
            default:
                throw new IllegalArgumentException();
        }
        return bandType;
    }

    /**
     * set country code for vendor hostapd.
     */
    public void setCountryCode(String countryCode) {
        mCountryCode = countryCode;
    }

    /**
     * Check if the device is running hostapd vendor service.
     * @return
     */
    public boolean isVendorHostapdHal() {
        return mIHostapdVendor != null;
    }

    /**
     * Returns false if Hostapd is null, and logs failure to call methodStr
     */
    private boolean checkHostapdAndLogFailure(String methodStr) {
        synchronized (mLock) {
            if (mIHostapd == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IHostapd is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(HostapdStatus status,
            String methodStr) {
        synchronized (mLock) {
            if (status.code != HostapdStatusCode.SUCCESS) {
                Log.e(TAG, "IHostapd." + methodStr + " failed: " + status.code
                        + ", " + status.debugMessage);
                return false;
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "IHostapd." + methodStr + " succeeded");
                }
                return true;
            }
        }
    }


    /**
     * Returns false if HostapdVendor is null, and logs failure to call methodStr
     */
    private boolean checkHostapdVendorAndLogFailure(String methodStr) {
        synchronized (mLock) {
            if (mIHostapdVendor == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IHostapdVendor is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkVendorStatusAndLogFailure(HostapdStatus status,
            String methodStr) {
        synchronized (mLock) {
            if (status.code != HostapdStatusCode.SUCCESS) {
                Log.e(TAG, "IHostapdVendor." + methodStr + " failed: " + status.code
                        + ", " + status.debugMessage);
                return false;
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.e(TAG, "IHostapdVendor." + methodStr + " succeeded");
                }
                return true;
            }
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            hostapdServiceDiedHandler();
            Log.e(TAG, "IHostapd." + methodStr + " failed with exception", e);
        }
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }

    private static void logi(String s) {
        Log.i(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }

    private class HostapdVendorIfaceHalCallback extends IHostapdVendorIfaceCallback.Stub {
        private SoftApListener mSoftApListener;

        HostapdVendorIfaceHalCallback(@NonNull String ifaceName, SoftApListener listener) {
           mSoftApListener = listener;
        }

        @Override
        public void onStaConnected(byte[/* 6 */] bssid) {
                String bssidStr = NativeUtil.macAddressFromByteArray(bssid);
                mSoftApListener.onStaConnected(bssidStr);
        }

        @Override
        public void onStaDisconnected(byte[/* 6 */] bssid) {
                String bssidStr = NativeUtil.macAddressFromByteArray(bssid);
                mSoftApListener.onStaDisconnected(bssidStr);
        }
    }

    /** See IHostapdVendor.hal for documentation */
    private boolean registerVendorCallback(@NonNull String ifaceName,
            IHostapdVendor service, IHostapdVendorIfaceCallback callback) {
        synchronized (mLock) {
            final String methodStr = "registerVendorCallback";
            if (service == null) return false;
            try {
                HostapdStatus status =  service.registerVendorCallback(ifaceName, callback);
                return checkVendorStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
}
