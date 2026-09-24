package com.vortex.vpn.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import com.vortex.vpn.Prefs;

/** Restores the tunnel after a reboot when the user asked for it. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Prefs.init(context);
        if (!Prefs.getBoolean(Prefs.KEY_AUTO_START, false)) {
            return;
        }
        try {
            if (VpnService.prepare(context) == null) {
                VpnServiceVortex.start(context);
            }
        } catch (Throwable ignored) {
        }
    }
}
