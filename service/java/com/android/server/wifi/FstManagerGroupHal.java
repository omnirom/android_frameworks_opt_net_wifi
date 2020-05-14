/*
 * Copyright (c) 2019-2020, The Linux Foundation. All rights reserved.
 *
 * Not a Contribution.
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
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.Handler;
import android.os.HidlSupport.Mutable;
import android.os.HwRemoteBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.net.MacAddress;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiNative.SupplicantDeathEventHandler;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.annotation.concurrent.ThreadSafe;

import vendor.qti.hardware.fstman.V1_0.IFstManager;
import vendor.qti.hardware.fstman.V1_0.IFstGroup;
import vendor.qti.hardware.fstman.V1_0.IFstGroupCallback;
import vendor.qti.hardware.fstman.V1_0.FstManagerStatus;
import vendor.qti.hardware.fstman.V1_0.FstManagerStatusCode;

/**
 * Hal calls for bring up/shut down of the fst-manager daemon and for
 * sending requests to the fst-manager daemon.
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
@ThreadSafe
public class FstManagerGroupHal {
    private static final String TAG = "FstManagerGroupHal";

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;

    // HAL interface objects
    private IServiceManager mIServiceManager = null;
    private IFstManager mIFstManager;
    private HashMap<String, IFstGroup> mIFstGroups = new HashMap<>();
    private HashMap<String, IFstGroupCallback> mIFstGroupCallbacks =
            new HashMap<>();
    private SupplicantDeathEventHandler mDeathEventHandler;
    private ServiceManagerDeathRecipient mServiceManagerDeathRecipient;
    private FstManagerDeathRecipient mFstManagerDeathRecipient;
    // Death recipient cookie registered for current fst-manager instance.
    private long mDeathRecipientCookie = 0;
    private final Handler mEventHandler;

    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
        public void onRegistration(String fqName, String name, boolean preexisting) {
            synchronized (mLock) {
                if (mVerboseLoggingEnabled) {
                    Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                            + ", " + name + " preexisting=" + preexisting);
                }
                if (!initFstManagerService()) {
                    Log.e(TAG, "initalizing IFstManager failed.");
                    fstManagerServiceDiedHandler(mDeathRecipientCookie);
                } else {
                    Log.i(TAG, "Completed initialization of IFstManager.");
                }
            }
        }
    };
    private class ServiceManagerDeathRecipient implements HwRemoteBinder.DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IServiceManager died: cookie=" + cookie);
                    fstManagerServiceDiedHandler(mDeathRecipientCookie);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                }
            });
        }
    }
    private class FstManagerDeathRecipient implements HwRemoteBinder.DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IFstManager died: cookie=" + cookie);
                    fstManagerServiceDiedHandler(cookie);
                }
            });
        }
    }

    public FstManagerGroupHal(Looper looper) {
        mEventHandler = new Handler(looper);

        mServiceManagerDeathRecipient = new ServiceManagerDeathRecipient();
        mFstManagerDeathRecipient = new FstManagerDeathRecipient();
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

    private boolean linkToServiceManagerDeath() {
        synchronized (mLock) {
            if (mIServiceManager == null) return false;
            try {
                if (!mIServiceManager.linkToDeath(mServiceManagerDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    fstManagerServiceDiedHandler(mDeathRecipientCookie);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IServiceManager.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    /**
     * Registers a service notification for the IFstManager service, which triggers initialization
     * of the IFstManager
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Registering IFstManager service ready callback.");
            }
            mIFstManager = null;
            mIFstGroups.clear();
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered,
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
                /* TODO(b/33639391) : Use the new IFstManager.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(
                        IFstManager.kInterfaceName, "default", mServiceNotificationCallback)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + IFstManager.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for IFstManager service: "
                        + e);
                fstManagerServiceDiedHandler(mDeathRecipientCookie);
            }
            return true;
        }
    }

    private boolean linkToFstManagerDeath() {
        synchronized (mLock) {
            if (mIFstManager == null) return false;
            try {
                if (!mIFstManager.linkToDeath(mFstManagerDeathRecipient, ++mDeathRecipientCookie)) {
                    Log.wtf(TAG, "Error on linkToDeath on IFstManager");
                    fstManagerServiceDiedHandler(mDeathRecipientCookie);
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IFstManager.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    private boolean initFstManagerService() {
        synchronized (mLock) {
            try {
                mIFstManager = getFstManagerMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "IFstManager.getService exception: " + e);
                return false;
            } catch (NoSuchElementException e) {
                Log.e(TAG, "IFstManager.getService exception: " + e);
                return false;
            }

            if (mIFstManager == null) {
                Log.e(TAG, "Got null IFstManager service. Stopping fst-manager HIDL startup");
                return false;
            }
            if (!linkToFstManagerDeath()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Setup an FST group with the specified name.
     * Currently assumes the group was already added to fst-manager
     *
     * @param groupName Name of the group. Set to NULL to setup the 1st known group
     * @return groupName on success, NULL otherwise.
     */
    public String setupGroup(String groupName) {
        synchronized (mLock) {
            final String methodStr = "setupGroup";
            Mutable<String> gName = new Mutable<String>();
            if (!TextUtils.isEmpty(groupName))
                if (!checkFstGroupExistsAndLogFailure(groupName, methodStr)) return null;
            if (!checkFstManagerAndLogFailure(methodStr)) return null;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "setupGroup got null group");
                return null;
            }
            if (TextUtils.isEmpty(groupName)) {
                try {
                    group.getName((FstManagerStatus status, String name) -> {
                        if (status.code != FstManagerStatusCode.SUCCESS) {
                            Log.e(TAG, "Getting group name failed: " + status.code);
                            return;
                        }
                        gName.value = name;
                    });
                } catch (RemoteException e) {
                    Log.e(TAG, "IFstGroup.getName exception: " + e);
                    handleException(e, "getName");
                }
                groupName = gName.value;
                if (TextUtils.isEmpty(groupName))
                    return null;
            }
            FstGroupHalCallback callback = new FstGroupHalCallback(groupName);

            if (!registerCallback(group, callback)) {
                return null;
            }
            mIFstGroups.put(groupName, group);
            mIFstGroupCallbacks.put(groupName, callback);

            return groupName;
        }
    }

    /**
     * Teardown an FST group for the specified group name.
     * Currently we do not remove the group from the fst-manager,
     * since we did not add it. Just remove the HAL objects,
     * which will be released by GC.
     *
     * @param groupName Name of the group.
     * @return true on success, false otherwise.
     */
    public boolean teardownGroup(@NonNull String groupName) {
        synchronized (mLock) {
            final String methodStr = "teardownGroup";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (mIFstGroups.remove(groupName) == null) {
                Log.e(TAG, "Trying to teardown unknown group");
                return false;
            }
            mIFstGroupCallbacks.remove(groupName);
            return true;
        }
    }

    /**
     * Get an FST group for the specified group name.
     *
     * @param groupName to find. Set to NULL to get 1st known group
     * @return FST group object on success, NULL otherwise
     */
    private IFstGroup getGroup(String groupName) {
        synchronized (mLock) {
            final String methodStr = "getGroup";
            if (mIFstManager == null) {
                return null;
            }

            /** List all FST groups */
            final ArrayList<String> fstGroups = new ArrayList<>();
            if (!checkFstManagerAndLogFailure(methodStr)) return null;
            try {
                mIFstManager.listGroups((FstManagerStatus status,
                                             ArrayList<String> groups) -> {
                    if (status.code != FstManagerStatusCode.SUCCESS) {
                        Log.e(TAG, "Getting FST groups failed: " + status.code);
                        return;
                    }
                    fstGroups.addAll(groups);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "IFstManager.listGroups exception: " + e);
                handleException(e, "listGroups");
                return null;
            }
            if (fstGroups.size() == 0) {
                Log.e(TAG, "Got zero HIDL FST groups. Stopping fst-manager HIDL startup.");
                return null;
            }
            Mutable<IFstGroup> fstGroup = new Mutable<>();
            for (String n : fstGroups) {
                if (TextUtils.isEmpty(groupName) || groupName.equals(n)) {
                    try {
                        mIFstManager.getGroup(n,
                                (FstManagerStatus status, IFstGroup group) -> {
                                    if (status.code != FstManagerStatusCode.SUCCESS) {
                                        Log.e(TAG, "Failed to get IFstGroup " + status.code);
                                        return;
                                    }
                                    fstGroup.value = group;
                                });
                    } catch (RemoteException e) {
                        Log.e(TAG, "IFstManager.getGroup exception: " + e);
                        handleException(e, "getGroup");
                        return null;
                    }
                    break;
                }
            }
            return fstGroup.value;
        }
    }

    /**
     * Registers a death notification for fst-manager.
     * @return Returns true on success.
     */
    public boolean registerDeathHandler(@NonNull SupplicantDeathEventHandler handler) {
        synchronized (mLock) {
            if (mDeathEventHandler != null) {
                Log.e(TAG, "Death handler already present");
            }
            mDeathEventHandler = handler;
            return true;
        }
    }

    /**
     * Deregisters a death notification for fst-manager.
     * @return Returns true on success.
     */
    public boolean deregisterDeathHandler() {
        synchronized (mLock) {
            if (mDeathEventHandler == null) {
                Log.e(TAG, "No Death handler present");
            }
            mDeathEventHandler = null;
            return true;
        }
    }


    private void clearState() {
        synchronized (mLock) {
            mIFstManager = null;
            mIFstGroups.clear();
            mIFstGroupCallbacks.clear();
        }
    }

    private void fstManagerServiceDiedHandler(long cookie) {
        synchronized (mLock) {
            if (mDeathRecipientCookie != cookie) {
                Log.i(TAG, "Ignoring stale death recipient notification");
                return;
            }
            for (String groupName : mIFstGroups.keySet()) {
                // TODO implement the below broadcast
                //mWifiMonitor.broadcastFstManagerDisconnectionEvent(groupName);
            }
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
            return mIFstManager != null;
        }
    }


    /**
     * Start the fst-manager daemon
     *
     * @return true on success, false otherwise.
     */
    public boolean startDaemon() {
        synchronized (mLock) {
            try {
                // This should startup fst-manager daemon using the lazy start HAL mechanism.
                getFstManagerMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to start fst-manager: "
                        + e);
                fstManagerServiceDiedHandler(mDeathRecipientCookie);
                return false;
            } catch (NoSuchElementException e) {
                // We're starting the daemon, so expect |NoSuchElementException|.
                Log.d(TAG, "Successfully triggered start of fst-manager using HIDL");
            }
            return true;
        }
    }

    /**
     * Terminate the fst-manager daemon
     */
    public void terminate() {
        synchronized (mLock) {
            final String methodStr = "terminate";
            if (!checkFstManagerAndLogFailure(methodStr)) return;
            try {
                mIFstManager.terminate();
            } catch (RemoteException e) {
                handleException(e, methodStr);
            } catch (NoSuchElementException e) {
                handleException(e, methodStr);
            }
        }
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        synchronized (mLock) {
            return IServiceManager.getService();
        }
    }

    protected IFstManager getFstManagerMockable() throws RemoteException, NoSuchElementException {
        synchronized (mLock) {
            return IFstManager.getService();
        }
    }

    /**
     * Helper method to look up the group object for the specified name.
     */
    private IFstGroup getGroupLocal(@NonNull String groupName) {
        return mIFstGroups.get(groupName);
    }

    private boolean registerCallback(
            IFstGroup group, IFstGroupCallback callback) {
        synchronized (mLock) {
            final String methodStr = "registerCallback";
            if (group == null) return false;
            try {
                FstManagerStatus status =  group.registerCallback(callback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleException(e, methodStr);
                return false;
            }
        }
    }

    public ArrayList<String> listInterfaces(String groupName) {
        ArrayList<String> groupIfaces = new ArrayList<String>();

        synchronized (mLock) {
            final String methodStr = "listInterfaces";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return groupIfaces;
            if (!checkFstManagerAndLogFailure(methodStr)) return groupIfaces;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "listInterfaces got null group");
                return groupIfaces;
            }

            try {
                group.listInterfaces((FstManagerStatus status,
                                      ArrayList<String> ifaces) -> {
                    if (status.code != FstManagerStatusCode.SUCCESS) {
                        Log.e(TAG, "Getting group interfaces failed: " + status.code);
                        return;
                    }
                    groupIfaces.addAll(ifaces);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "IFstGroup.listInterfaces exception: " + e);
                handleException(e, "listInterfaces");
            }
        }
        return groupIfaces;
    }

    public boolean isFstModeSupported(String groupName) {
        Mutable<Boolean> result = new Mutable<Boolean>(Boolean.FALSE);

        synchronized (mLock) {
            final String methodStr = "isFstModeSupported";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (!checkFstManagerAndLogFailure(methodStr)) return false;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "isFstModeSupported got null group");
                return false;
            }

            try {
                group.isFstModeSupported((FstManagerStatus status,
                                          boolean supported) -> {
                    if (status.code != FstManagerStatusCode.SUCCESS) {
                        Log.e(TAG, "failed isFstModeSupported: " + status.code);
                        return;
                    }
                    result.value = Boolean.valueOf(supported);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "IFstGroup.isFstModeSupported exception: " + e);
                handleException(e, "isFstModeSupported");
            }
        }
        return result.value.booleanValue();
    }

    public boolean isWifiSonModeSupported(String groupName) {
        Mutable<Boolean> result = new Mutable<Boolean>(Boolean.FALSE);

        synchronized (mLock) {
            final String methodStr = "isWifiSonModeSupported";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (!checkFstManagerAndLogFailure(methodStr)) return false;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "isWifiSonModeSupported got null group");
                return false;
            }

            try {
                group.isWifiSonModeSupported((FstManagerStatus status,
                                              boolean supported) -> {
                    if (status.code != FstManagerStatusCode.SUCCESS) {
                        Log.e(TAG, "failed isWifiSonModeSupported: " + status.code);
                        return;
                    }
                    result.value = Boolean.valueOf(supported);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "IFstGroup.isWifiSonModeSupported exception: " + e);
                handleException(e, "isWifiSonModeSupported");
            }
        }
        return result.value.booleanValue();
    }

    public String getMuxInterfaceName(String groupName) {
        Mutable<String> result = new Mutable<>();

        synchronized (mLock) {
            final String methodStr = "getMuxInterfaceName";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return null;
            if (!checkFstManagerAndLogFailure(methodStr)) return null;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "getMuxInterfaceName got null group");
                return null;
            }

            try {
                group.getMuxInterfaceName((FstManagerStatus status,
                                           String ifaceName) -> {
                    if (status.code != FstManagerStatusCode.SUCCESS) {
                        Log.e(TAG, "failed getMuxInterfaceName: " + status.code);
                        return;
                    }
                    result.value = ifaceName;
                });
            } catch (RemoteException e) {
                Log.e(TAG, "IFstGroup.getMuxInterfaceName exception: " + e);
                handleException(e, "getMuxInterfaceName");
            }
        }
        return result.value;
    }

    public boolean setMuxInterfaceName(String groupName, String ifaceName) {
        synchronized (mLock) {
            final String methodStr = "setMuxInterfaceName";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (!checkFstManagerAndLogFailure(methodStr)) return false;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "setMuxInterfaceName got null group");
                return false;
            }

            try {
                FstManagerStatus status = group.setMuxInterfaceName(ifaceName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleException(e, methodStr);
                return false;
            }
        }
    }

    public boolean enslave(
            String groupName, String ifname, boolean enslave) {
        synchronized (mLock) {
            final String methodStr = "enslave";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (!checkFstManagerAndLogFailure(methodStr)) return false;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "enslave got null group");
                return false;
            }

            try {
                FstManagerStatus status = group.enslave(ifname, enslave);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleException(e, methodStr);
                return false;
            }
        }
    }

    public boolean isEnslaved(String groupName, String ifaceName) {
        Mutable<Boolean> result = new Mutable<Boolean>(Boolean.FALSE);

        synchronized (mLock) {
            final String methodStr = "isEnslaved";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (!checkFstManagerAndLogFailure(methodStr)) return false;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "enslave got null group");
                return false;
            }

            try {
                group.isEnslaved(ifaceName, (FstManagerStatus status,
                                             boolean supported) -> {
                    if (status.code != FstManagerStatusCode.SUCCESS) {
                        Log.e(TAG, "failed isEnslaved: " + status.code);
                        return;
                    }
                    result.value = Boolean.valueOf(supported);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "IFstGroup.isEnslaved exception: " + e);
                handleException(e, "isEnslaved");
            }
        }
        return result.value.booleanValue();
    }

    public boolean setMacAddress(String groupName, MacAddress macAddr) {
        byte[] macByteArray = macAddr.toByteArray();
        synchronized (mLock) {
            final String methodStr = "setMacAddress";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (!checkFstManagerAndLogFailure(methodStr)) return false;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "setMacAddress got null group");
                return false;
            }

            try {
                FstManagerStatus status = group.setMacAddress(macByteArray);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleException(e, methodStr);
                return false;
            }
        }
    }

    public boolean isRateUpgradeMaster(String groupName, String ifaceName) {
        Mutable<Boolean> result = new Mutable<Boolean>(Boolean.FALSE);

        synchronized (mLock) {
            final String methodStr = "isRateUpgradeMaster";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (!checkFstManagerAndLogFailure(methodStr)) return false;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "isRateUpgradeMaster got null group");
                return false;
            }

            try {
                group.isRateUpgradeMaster(ifaceName, (FstManagerStatus status,
                                                      boolean isMaster) -> {
                    if (status.code != FstManagerStatusCode.SUCCESS) {
                        Log.e(TAG, "failed isRateUpgradeMaster: " + status.code);
                        return;
                    }
                    result.value = Boolean.valueOf(isMaster);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "IFstGroup.isRateUpgradeMaster exception: " + e);
                handleException(e, "isRateUpgradeMaster");
            }
        }
        return result.value.booleanValue();
    }

    public boolean renameInterface(String groupName, String ifaceName, String newIfaceName) {
        synchronized (mLock) {
            final String methodStr = "renameInterface";
            if (!checkFstGroupAndLogFailure(groupName, methodStr)) return false;
            if (!checkFstManagerAndLogFailure(methodStr)) return false;

            IFstGroup group = getGroup(groupName);
            if (group == null) {
                Log.e(TAG, "renameInterface got null group");
                return false;
            }

            try {
                FstManagerStatus status = group.renameInterface(ifaceName, newIfaceName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Returns false if FstManager is null, and logs failure to call methodStr
     */
    private boolean checkFstManagerAndLogFailure(final String methodStr) {
        synchronized (mLock) {
            if (mIFstManager == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IFstManager is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns false if FstGroup is null, and logs failure to call methodStr
     */
    private boolean checkFstGroupAndLogFailure(
            @NonNull String groupName, final String methodStr) {
        synchronized (mLock) {
            IFstGroup group = getGroupLocal(groupName);
            if (group == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IFstGroup is null");
                return false;
            }
            return true;
        }
    }

    /**
     * returns true if FST group not yet obtained,
     * and logs failure to call methodStr if group exists
     */
    private boolean checkFstGroupExistsAndLogFailure(
            @NonNull String groupName, final String methodStr) {
        synchronized (mLock) {
            IFstGroup group = getGroupLocal(groupName);
            if (group != null) {
                Log.e(TAG, "Can't call " + methodStr + ", IFstGroup not null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(FstManagerStatus status,
            final String methodStr) {
        synchronized (mLock) {
            if (status.code != FstManagerStatusCode.SUCCESS) {
                Log.e(TAG, "IFstGroup." + methodStr + " failed: " + status);
                return false;
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "IFstGroup." + methodStr + " succeeded");
                }
                return true;
            }
        }
    }

    /**
     * Helper function to log callbacks.
     */
    private void logCallback(final String methodStr) {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "IFstGroupCallback." + methodStr + " received");
            }
        }
    }

    private void handleException(Throwable e, String methodStr) {
        synchronized (mLock) {
            clearState();
            Log.e(TAG, "IFstGroup." + methodStr + " failed with exception", e);
        }
    }

    private class FstGroupHalCallback extends IFstGroupCallback.Stub {
        private String mGroupName;

        FstGroupHalCallback(@NonNull String groupName) {
            mGroupName = groupName;
        }
    }
}
