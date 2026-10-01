package com.lody.virtual.client.stub;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;

import java.io.File;


/**
 * @author Lody
 */
public class DaemonService extends Service {

    private static final int NOTIFY_ID = 1001;
    private static final String CHANNEL_ID = "DaemonServiceNotification";

	static boolean showNotification = true;

	public static void startup(Context context) {
		File flagFile = context.getFileStreamPath(Constants.NO_NOTIFICATION_FLAG);
		if (Build.VERSION.SDK_INT >= 25 && flagFile.exists()) {
			showNotification = false;
		}

		context.startService(new Intent(context, DaemonService.class));
		if (VirtualCore.get().isServerProcess()) {
			// PrivilegeAppOptimizer.notifyBootFinish();
			DaemonJobService.scheduleJob(context);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		startup(this);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (!showNotification) {
			return;
		}
        startService(new Intent(this, InnerService.class));
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            startForeground(NOTIFY_ID, new Notification());
            return;
        }

        startServiceForeground(this);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	public static final class InnerService extends Service {

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
        	if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                startForeground(NOTIFY_ID, new Notification());
            } else {
				startServiceForeground(this);
			}
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

		@Override
		public IBinder onBind(Intent intent) {
			return null;
		}
	}

    @SuppressLint("WrongConstant")
    public static void startServiceForeground(Service context) {
        String NOTIFICATION_CHANNEL_ID = VirtualCore.get().getHostPkg() + "_FOREGROUND_NOTIFICATION";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "Foreground Service", NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableLights(true);
            channel.setLightColor(Color.BLACK);

            if (manager != null)
                manager.createNotificationChannel(channel);
        }

        int NOTIFICATION_ID = 2;
        context.startForeground(NOTIFICATION_ID, new Notification.Builder(context, NOTIFICATION_CHANNEL_ID).build());
    }
}
