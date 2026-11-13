/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;

import ru.playsoftware.j2meloader.grid.SlotProtocol;

/**
 * Runs inside a slot process. Turns the MicroActivity window into a small floating window
 * that lies over one cell of the grid (or over the whole grid area when maximized) and
 * executes the commands sent by the grid host.
 */
final class SlotController {
	private static final String TAG = SlotController.class.getSimpleName();

	private final MicroActivity activity;
	private final int slot;
	private volatile boolean alive = true;
	private boolean top;
	private int lastX, lastY, lastW = 1, lastH = 1, lastMode = SlotProtocol.MODE_GRID;

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null || intent.getIntExtra(SlotProtocol.EXTRA_SLOT, -1) != slot) {
				return;
			}
			try {
				handleCommand(intent);
			} catch (Throwable t) {
				Log.w(TAG, "Bad slot command", t);
			}
		}
	};

	SlotController(MicroActivity activity, int slot) {
		this.activity = activity;
		this.slot = slot;
	}

	/** Called from onCreate, before the content view is created. */
	void onCreate(Intent intent) {
		MidletThread.setSlotIndex(slot);
		try {
			Intent svc = new Intent(activity, ru.playsoftware.j2meloader.grid.SlotKeepAliveService.classFor(slot));
			if (Build.VERSION.SDK_INT >= 26) {
				activity.startForegroundService(svc);
			} else {
				activity.startService(svc);
			}
		} catch (Throwable t) {
			Log.w(TAG, "keepalive service", t);
		}
		activity.setFinishOnTouchOutside(false);
		applyLayout(intent.getIntExtra(SlotProtocol.EXTRA_X, 0),
				intent.getIntExtra(SlotProtocol.EXTRA_Y, 0),
				intent.getIntExtra(SlotProtocol.EXTRA_W, 1),
				intent.getIntExtra(SlotProtocol.EXTRA_H, 1),
				intent.getIntExtra(SlotProtocol.EXTRA_MODE, SlotProtocol.MODE_GRID));
		SlotProtocol.register(activity, receiver, SlotProtocol.ACTION_CMD);
		Thread heartbeat = new Thread(() -> {
			while (alive) {
				try {
					activity.sendBroadcast(SlotProtocol.eventIntent(activity, slot, SlotProtocol.EVT_ALIVE));
					Thread.sleep(1500);
				} catch (InterruptedException e) {
					return;
				} catch (Throwable t) {
					Log.w(TAG, "heartbeat", t);
				}
			}
		}, "SlotHeartbeat");
		heartbeat.setDaemon(true);
		heartbeat.start();
	}

	void onDestroy() {
		alive = false;
		try {
			activity.stopService(new Intent(activity,
					ru.playsoftware.j2meloader.grid.SlotKeepAliveService.classFor(slot)));
		} catch (Throwable ignored) {
		}
		try {
			activity.unregisterReceiver(receiver);
		} catch (IllegalArgumentException ignored) {
		}
	}

	void onResumed() {
		top = true;
		// the window class resets size/position while the decor is installed: apply them again
		reapplyLayout();
		activity.getWindow().getDecorView().post(this::reapplyLayout);
	}

	private void reapplyLayout() {
		applyLayout(lastX, lastY, lastW, lastH, lastMode);
	}

	void onPaused() {
		top = false;
	}

	/** Any touch on a slot window that is not on top brings it to front (focus, dialogs). */
	void onTouch(MotionEvent ev) {
		if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !top) {
			bringToFront();
		}
	}

	void bringToFront() {
		if (top) {
			return;
		}
		try {
			Intent intent = new Intent(activity, activity.getClass());
			intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
			activity.startActivity(intent);
			activity.overridePendingTransition(0, 0);
		} catch (Throwable t) {
			Log.w(TAG, "bringToFront", t);
		}
	}

	void requestToggleMax() {
		activity.sendBroadcast(SlotProtocol.eventIntent(activity, slot, SlotProtocol.EVT_TOGGLE_MAX));
	}

	private void handleCommand(Intent intent) {
		switch (intent.getIntExtra(SlotProtocol.EXTRA_CMD, 0)) {
			case SlotProtocol.CMD_LAYOUT:
				int mode = intent.getIntExtra(SlotProtocol.EXTRA_MODE, SlotProtocol.MODE_GRID);
				applyLayout(intent.getIntExtra(SlotProtocol.EXTRA_X, 0),
						intent.getIntExtra(SlotProtocol.EXTRA_Y, 0),
						intent.getIntExtra(SlotProtocol.EXTRA_W, 1),
						intent.getIntExtra(SlotProtocol.EXTRA_H, 1),
						mode);
				if (mode == SlotProtocol.MODE_MAX) {
					bringToFront();
				}
				break;
			case SlotProtocol.CMD_STOP:
				MidletThread.destroyApp();
				break;
			case SlotProtocol.CMD_PAUSE:
				MidletThread.pauseApp();
				break;
			case SlotProtocol.CMD_RESUME:
				MidletThread.resumeAppForced();
				break;
			default:
				break;
		}
	}

	/**
	 * Moves/resizes the window. Only window attributes change, the activity and its surface
	 * are kept, so a grid <-> maximized switch does not restart or recreate anything.
	 */
	private void applyLayout(int x, int y, int w, int h, int mode) {
		lastX = x;
		lastY = y;
		lastW = w;
		lastH = h;
		lastMode = mode;
		// The throttle is changed before the resize so the first frame at the new size is not delayed
		Canvas.setGridThrottle(mode != SlotProtocol.MODE_MAX);
		Window window = activity.getWindow();
		WindowManager.LayoutParams lp = window.getAttributes();
		lp.gravity = Gravity.TOP | Gravity.LEFT;
		lp.x = x;
		lp.y = y;
		lp.width = Math.max(1, w);
		lp.height = Math.max(1, h);
		lp.dimAmount = 0f;
		lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
				| WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
				| WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
		if (mode == SlotProtocol.MODE_HIDDEN) {
			lp.alpha = 0f;
			lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
		} else {
			lp.alpha = 1f;
			lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
		}
		if (Build.VERSION.SDK_INT >= 28) {
			lp.layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		}
		window.setAttributes(lp);
		if (mode == SlotProtocol.MODE_MAX) {
			Displayable current = activity.getCurrent();
			if (current instanceof Canvas) {
				((Canvas) current).presentNow();
			}
		}
	}
}
