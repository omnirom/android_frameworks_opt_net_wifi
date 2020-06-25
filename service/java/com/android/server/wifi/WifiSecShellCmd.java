
package com.android.server.wifi;

import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;

import java.io.PrintWriter;
import java.util.List;
import com.android.internal.util.AsyncChannel;
import android.text.TextUtils;
import android.os.Process;
import android.os.Message;
import android.content.Context;

import android.net.NetworkRequest;
import static android.net.wifi.WifiManager.STA_PRIMARY;
import static android.net.wifi.WifiManager.STA_SECONDARY;
import android.net.NetworkCapabilities;
/**
 * Runs shell command for secondary station.
 *
 * To run 'adb shell cmd wifi sec [args]'
 *
 * API of this class is invoked by WifiShellCommand.
 */
public class WifiSecShellCmd {

    private final ConnectivityManager mConnectivityManager;
    private final WifiInjector mWifiInjector;
    private final ActiveModeWarden mActiveModeWarden;
    private final Context mContext;
    private final int mStaId;
    private WifiManager mWifiManager;

    WifiSecShellCmd(WifiInjector injector) {
        mWifiInjector = injector;
        mActiveModeWarden = injector.getActiveModeWarden();
        mContext = injector.getContext();
        mStaId = STA_SECONDARY;
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mWifiManager = null;
    }

    private WifiManager getManager() {
        if (mWifiManager == null)
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        return mWifiManager;
    }

/* Lets do it through WifiManager */

    public void enable() {
       mConnectivityManager.requestNetwork(new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID)
                            .build(),
                  new ConnectivityManager.NetworkCallback());
       getManager().setWifiEnabled(mStaId, true);
    }

    public void disable() {
        getManager().setWifiEnabled(mStaId, false);
    }

    public int addNetwork(String ssid, String password) {
        return getManager().addNetwork(createLocalConfig(ssid, password));
    }

    public int updateNetwork(String ssid, String password) {
        return getManager().updateNetwork(createLocalConfig(ssid, password));
    }

    public void saveNetwork(String ssid, String password) {
        getManager().save(createLocalConfig(ssid, password), null);
    }

    public boolean removeNetwork(int netId) {
        return getManager().removeNetwork(netId);
    }


    public void forgetNetwork(int netId) {
        getManager().forget(netId, null);
    }

    public void connectNetwork(String ssid, String password) {
        getManager().connect(createLocalConfig(ssid, password), null);
    }

    public void connectNetwork(int netId) {
        getManager().connect(netId, null);
    }

    public boolean enableNetwork(int netId) {
        return getManager().enableNetwork(netId, false /*disable others*/);
    }

    public void disableNetwork(int netId) {
        getManager().disable(netId, null);
    }

    public boolean disconnect() {
        return getManager().disconnect(mStaId);
    }

    public void listNetwork(PrintWriter pw) {
        List<WifiConfiguration> configs = null;

        configs = getManager().getConfiguredNetworks(mStaId);
        if (configs != null)
            pw.println("Total configurations: " + configs.size());

        for (WifiConfiguration config : configs) {
            pw.println(" [" + getStaIdString(config) + "] netId: " + config.networkId
                       + " SSID: " + config.SSID + " configkey: " + config.getKey());
        }
    }

    public void status(PrintWriter pw) {
        pw.println("WifiInfo=" + getManager().getConnectionInfo(mStaId));
    }

    // Utility API

    private WifiConfiguration createLocalConfig(String ssid, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.staId = mStaId;
        config.SSID = "\"" + ssid + "\"";

        // WPA2-PSK or OPEN network
        if (TextUtils.isEmpty(password)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            config.preSharedKey = "\"" + password + "\"";
        }
        return config;
    }

    private String getStaIdString(WifiConfiguration config) {
        if (config == null)
            return "config is null";

        switch (config.staId) {
            case STA_PRIMARY:
                return "PRIMARY";
            case STA_SECONDARY:
                return "SECONDARY";
            default:
                return "UNKNOWN";
        }
    }
}
