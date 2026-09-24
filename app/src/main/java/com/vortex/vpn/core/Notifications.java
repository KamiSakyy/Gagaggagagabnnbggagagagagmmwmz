package com.vortex.vpn.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.vortex.vpn.App;
import com.vortex.vpn.R;
import com.vortex.vpn.ui.MainActivity;

/** Notification channels and the ongoing VPN notification. */
public final class Notifications {

    public static final String CHANNEL_VPN = "vpn";
    public static final String CHANNEL_ENGINE = "engine";
    public static final int ID_VPN = 1001;

    private Notifications() {
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel vpn = new NotificationChannel(CHANNEL_VPN,
                context.getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW);
        vpn.setShowBadge(false);
        vpn.setSound(null, null);
        vpn.enableVibration(false);
        manager.createNotificationChannel(vpn);

        NotificationChannel engine = new NotificationChannel(CHANNEL_ENGINE,
                context.getString(R.string.channel_engine), NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(engine);
    }

    public static Notification build(Context context, boolean running, String title, String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(context, VpnServiceVortex.class);
        stopIntent.setAction(VpnServiceVortex.ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(context, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggleIntent = new Intent(context, VpnServiceVortex.class);
        toggleIntent.setAction(running ? VpnServiceVortex.ACTION_STOP : VpnServiceVortex.ACTION_START);
        PendingIntent togglePending = PendingIntent.getService(context, 3, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_VPN)
                .setSmallIcon(running ? R.drawable.ic_shield_check : R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(running)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .addAction(0, context.getString(running ? R.string.action_disconnect : R.string.action_connect),
                        togglePending);
        if (running) {
            builder.addAction(0, context.getString(R.string.action_stop_service), stopPending);
        }
        return builder.build();
    }

    public static void update(Context context, boolean running, String title, String text) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.notify(ID_VPN, build(context, running, title, text));
    }

    public static void engine(Context context, String title, String text) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ENGINE)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        manager.notify((int) (System.currentTimeMillis() & 0xFFFF), builder.build());
    }

    public static void cancelEngine(Context context, int id) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(id);
        }
    }

    /** Ensures the app context is available for background helpers. */
    public static Context appContext() {
        return App.get();
    }
}
