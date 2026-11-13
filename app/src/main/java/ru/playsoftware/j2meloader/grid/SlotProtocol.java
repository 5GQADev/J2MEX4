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

package ru.playsoftware.j2meloader.grid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Messages exchanged between the grid host (main process) and the slot processes
 * (one process per game). Everything travels as package-private broadcasts.
 */
public final class SlotProtocol {
	public static final int SLOT_COUNT = 4;

	/** host -> slot */
	public static final String ACTION_CMD = "ru.playsoftware.j2meloader.grid.CMD";
	/** slot -> host */
	public static final String ACTION_EVENT = "ru.playsoftware.j2meloader.grid.EVENT";

	public static final String EXTRA_SLOT = "slot";
	public static final String EXTRA_CMD = "cmd";
	public static final String EXTRA_EVENT = "event";
	public static final String EXTRA_X = "x";
	public static final String EXTRA_Y = "y";
	public static final String EXTRA_W = "w";
	public static final String EXTRA_H = "h";
	public static final String EXTRA_MODE = "mode";
	public static final String EXTRA_PATH = "path";

	// commands (host -> slot)
	public static final int CMD_LAYOUT = 1;
	public static final int CMD_STOP = 2;
	public static final int CMD_PAUSE = 3;
	public static final int CMD_RESUME = 4;

	// events (slot -> host)
	public static final int EVT_ALIVE = 1;
	public static final int EVT_STOPPED = 2;
	public static final int EVT_TOGGLE_MAX = 3;
	/** result of the file picker / settings screen, sent by GridProxyActivity */
	public static final int EVT_PROXY_RESULT = 4;

	// window modes
	/** small cell, presents 1 frame per second */
	public static final int MODE_GRID = 0;
	/** whole area, presents frames normally */
	public static final int MODE_MAX = 1;
	/** invisible and untouchable (another slot is maximized, or a host dialog is open) */
	public static final int MODE_HIDDEN = 2;

	private SlotProtocol() {
	}

	public static Intent layoutIntent(Context context, int slot, int x, int y, int w, int h, int mode) {
		return base(context, ACTION_CMD, slot)
				.putExtra(EXTRA_CMD, CMD_LAYOUT)
				.putExtra(EXTRA_X, x)
				.putExtra(EXTRA_Y, y)
				.putExtra(EXTRA_W, w)
				.putExtra(EXTRA_H, h)
				.putExtra(EXTRA_MODE, mode);
	}

	public static Intent commandIntent(Context context, int slot, int cmd) {
		return base(context, ACTION_CMD, slot).putExtra(EXTRA_CMD, cmd);
	}

	public static Intent eventIntent(Context context, int slot, int event) {
		return base(context, ACTION_EVENT, slot).putExtra(EXTRA_EVENT, event);
	}

	private static Intent base(Context context, String action, int slot) {
		Intent intent = new Intent(action);
		intent.setPackage(context.getPackageName());
		intent.putExtra(EXTRA_SLOT, slot);
		return intent;
	}

	/** Registers a receiver that is not visible to other apps where the platform allows it. */
	public static void register(Context context, BroadcastReceiver receiver, String action) {
		IntentFilter filter = new IntentFilter(action);
		if (Build.VERSION.SDK_INT >= 33) {
			context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
		} else {
			context.registerReceiver(receiver, filter);
		}
	}
}
