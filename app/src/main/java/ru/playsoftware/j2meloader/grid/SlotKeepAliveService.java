package ru.playsoftware.j2meloader.grid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service living in a slot process. It raises the process importance so Android
 * does not kill a slot that is not the focused one ("excessive cpu" / background limits).
 */
public class SlotKeepAliveService extends Service {
	private static final String CHANNEL = "slot_keepalive";

	protected int slot() {
		return 0;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int s = slot();
		Notification.Builder b;
		if (Build.VERSION.SDK_INT >= 26) {
			NotificationManager nm = getSystemService(NotificationManager.class);
			nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Game slots",
					NotificationManager.IMPORTANCE_MIN));
			b = new Notification.Builder(this, CHANNEL);
		} else {
			b = new Notification.Builder(this);
		}
		b.setSmallIcon(getApplicationInfo().icon)
				.setContentTitle("Slot " + (s + 1) + " đang chạy")
				.setOngoing(true);
		startForeground(100 + s, b.build());
		return START_NOT_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	public static class S0 extends SlotKeepAliveService {
		@Override protected int slot() { return 0; }
	}

	public static class S1 extends SlotKeepAliveService {
		@Override protected int slot() { return 1; }
	}

	public static class S2 extends SlotKeepAliveService {
		@Override protected int slot() { return 2; }
	}

	public static class S3 extends SlotKeepAliveService {
		@Override protected int slot() { return 3; }
	}

	public static Class<?> classFor(int slot) {
		switch (slot) {
			case 1: return S1.class;
			case 2: return S2.class;
			case 3: return S3.class;
			default: return S0.class;
		}
	}
}
