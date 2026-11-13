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

import static ru.playsoftware.j2meloader.util.Constants.ACTION_EDIT;
import static ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME;
import static ru.playsoftware.j2meloader.util.Constants.KEY_SLOT;
import static ru.playsoftware.j2meloader.util.Constants.KEY_SLOT_START;
import static ru.playsoftware.j2meloader.util.Constants.PREF_KEEP_SCREEN;
import static ru.playsoftware.j2meloader.util.Constants.PREF_LAST_PATH;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Map;

import javax.microedition.shell.SlotActivity0;
import javax.microedition.shell.SlotActivity1;
import javax.microedition.shell.SlotActivity2;
import javax.microedition.shell.SlotActivity3;

import ru.playsoftware.j2meloader.MainActivity;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ConfigActivity;
import ru.playsoftware.j2meloader.config.ProfilesActivity;
import ru.playsoftware.j2meloader.settings.SettingsActivity;
import ru.playsoftware.j2meloader.util.FileUtils;

/**
 * Launcher screen: a 2x2 grid with four independent game slots. Every slot runs its game in
 * its own process; the game window of a slot is a floating window laid over the cell
 * (see SlotController), so the whole grid is one task and one entry in the recent apps.
 */
public class GridActivity extends AppCompatActivity {
	private static final String TAG = GridActivity.class.getSimpleName();
	private static final String PREFS_SLOTS = "grid_slots";
	private static final long ALIVE_TIMEOUT = 6000;
	private static final long STOP_TIMEOUT = 3500;
	private static final long ADOPT_GUARD = 4000;
	private static final long PAUSE_DELAY = 700;

	private static WeakReference<GridActivity> sInstance;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Slot[] slots = new Slot[SlotProtocol.SLOT_COUNT];
	private SlotGridLayout grid;
	private SharedPreferences slotPrefs;
	private SharedPreferences appPrefs;
	private int maxSlot = -1;
	private int hideCount;
	private int pendingPickSlot = -1;
	private int pendingConfigSlot = -1;
	private int startedActivities;
	private boolean pauseSent;

	private final ActivityResultLauncher<String> pickLauncher =
			registerForActivityResult(FileUtils.getFilePicker(), this::onPicked);
	private final ActivityResultLauncher<Intent> configLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), this::onConfigResult);
	private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
			new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

	private final Runnable pushRunnable = this::pushLayout;

	private final Runnable watchdog = new Runnable() {
		@Override
		public void run() {
			long now = SystemClock.uptimeMillis();
			for (Slot s : slots) {
				if (s != null && s.running) {
					if ((s.stopRequested && now > s.stopDeadline) || now - s.lastAlive > ALIVE_TIMEOUT) {
						markStopped(s);
					}
				}
			}
			handler.postDelayed(this, 1000);
		}
	};

	private final Runnable pauseRunnable = () -> {
		if (startedActivities == 0) {
			pauseSent = true;
			for (Slot s : slots) {
				if (s.running) {
					sendBroadcast(SlotProtocol.commandIntent(this, s.index, SlotProtocol.CMD_PAUSE));
				}
			}
		}
	};

	/** The games are paused only when the whole app goes to background, not when one of our screens opens. */
	private final Application.ActivityLifecycleCallbacks lifecycleCallbacks =
			new Application.ActivityLifecycleCallbacks() {
				@Override
				public void onActivityStarted(Activity activity) {
					startedActivities++;
					handler.removeCallbacks(pauseRunnable);
					if (pauseSent) {
						pauseSent = false;
						for (Slot s : slots) {
							if (s.running) {
								sendBroadcast(SlotProtocol.commandIntent(GridActivity.this, s.index,
										SlotProtocol.CMD_RESUME));
							}
						}
					}
				}

				@Override
				public void onActivityStopped(Activity activity) {
					startedActivities = Math.max(0, startedActivities - 1);
					if (startedActivities == 0) {
						handler.postDelayed(pauseRunnable, PAUSE_DELAY);
					}
				}

				@Override
				public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
				}

				@Override
				public void onActivityResumed(Activity activity) {
				}

				@Override
				public void onActivityPaused(Activity activity) {
				}

				@Override
				public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
				}

				@Override
				public void onActivityDestroyed(Activity activity) {
				}
			};

	private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null) {
				return;
			}
			int index = intent.getIntExtra(SlotProtocol.EXTRA_SLOT, -1);
			if (index < 0 || index >= slots.length) {
				return;
			}
			Slot s = slots[index];
			long now = SystemClock.uptimeMillis();
			switch (intent.getIntExtra(SlotProtocol.EXTRA_EVENT, 0)) {
				case SlotProtocol.EVT_ALIVE:
					if (s.running) {
						s.lastAlive = now;
					} else if (s.path != null && now - s.stoppedAt > ADOPT_GUARD) {
						// a slot process that is still alive (e.g. the host was restarted)
						s.running = true;
						s.stopRequested = false;
						s.lastAlive = now;
						s.lastKey = "";
						updateSlotUi(s);
						schedulePush();
					}
					break;
				case SlotProtocol.EVT_STOPPED:
					markStopped(s);
					break;
				case SlotProtocol.EVT_TOGGLE_MAX:
					toggleMax(index);
					break;
				case SlotProtocol.EVT_PROXY_RESULT:
					onProxyResult(index, intent);
					break;
				default:
					break;
			}
		}
	};

	private final class Slot {
		final int index;
		volatile String name;
		volatile String path;
		volatile boolean running;
		long lastAlive;
		long stoppedAt;
		long stopDeadline;
		boolean stopRequested;
		boolean restartPending;
		boolean converting;
		String lastKey = "";
		int lastX, lastY, lastW, lastH;
		LinearLayout cell;
		FrameLayout area;
		TextView label;
		ImageButton btnRun, btnSettings, btnPick, btnMax;

		Slot(int index) {
			this.index = index;
		}
	}

	// ---------------------------------------------------------------- lifecycle

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		GridActivity existing = sInstance != null ? sInstance.get() : null;
		if (existing != null && existing != this && !existing.isFinishing()) {
			// Started again (e.g. "open with") while the games are on top of the first instance
			existing.onNewIntent(getIntent());
			finish();
			return;
		}
		sInstance = new WeakReference<>(this);
		appPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		slotPrefs = getSharedPreferences(PREFS_SLOTS, MODE_PRIVATE);
		buildUi();
		loadSlots();
		SlotProtocol.register(this, eventReceiver, SlotProtocol.ACTION_EVENT);
		getApplication().registerActivityLifecycleCallbacks(lifecycleCallbacks);
		handler.postDelayed(watchdog, 1000);
		initStorage();
		if (savedInstanceState == null) {
			handleViewIntent(getIntent());
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		handleViewIntent(intent);
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (appPrefs.getBoolean(PREF_KEEP_SCREEN, false)) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		schedulePush();
	}

	@Override
	protected void onDestroy() {
		handler.removeCallbacksAndMessages(null);
		if (sInstance != null && sInstance.get() == this) {
			sInstance = null;
			try {
				unregisterReceiver(eventReceiver);
			} catch (IllegalArgumentException ignored) {
			}
			getApplication().unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
		}
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		if (maxSlot >= 0) {
			toggleMax(maxSlot);
		} else if (anyRunning()) {
			moveTaskToBack(true);
		} else {
			super.onBackPressed();
		}
	}

	// ---------------------------------------------------------------- UI

	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}

	private void buildUi() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(0xFF101010);

		LinearLayout top = new LinearLayout(this);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);
		top.setBackgroundColor(0xFF1E1E1E);
		TextView title = new TextView(this);
		title.setText(R.string.grid_app_title);
		title.setTextColor(0xFFE0E0E0);
		title.setTextSize(15);
		title.setPadding(dp(12), 0, dp(12), 0);
		top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		ImageButton more = makeButton(R.drawable.grid_ic_more, R.string.grid_menu_more, this::showMenu);
		top.addView(more, new LinearLayout.LayoutParams(dp(48), dp(36)));
		root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

		grid = new SlotGridLayout(this);
		for (int i = 0; i < slots.length; i++) {
			slots[i] = createSlot(i);
			grid.addView(slots[i].cell);
		}
		root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		setContentView(root);
		root.getViewTreeObserver().addOnGlobalLayoutListener(this::schedulePush);
	}

	private Slot createSlot(final int i) {
		final Slot s = new Slot(i);
		s.cell = new LinearLayout(this);
		s.cell.setOrientation(LinearLayout.VERTICAL);
		s.cell.setPadding(dp(1), dp(1), dp(1), dp(1));
		s.cell.setBackgroundColor(0xFF2B2B2B);

		s.area = new FrameLayout(this);
		s.area.setBackgroundColor(Color.BLACK);
		s.label = new TextView(this);
		s.label.setGravity(Gravity.CENTER);
		s.label.setTextColor(0xFFB0B0B0);
		s.label.setTextSize(13);
		s.label.setPadding(dp(8), dp(8), dp(8), dp(8));
		s.area.addView(s.label, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		s.cell.addView(s.area, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		LinearLayout bar = new LinearLayout(this);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setBackgroundColor(0xFF1E1E1E);
		s.btnRun = makeButton(R.drawable.grid_ic_play, R.string.grid_run, v -> onRunClicked(i));
		s.btnSettings = makeButton(R.drawable.grid_ic_settings, R.string.grid_settings, v -> openSettings(i));
		s.btnPick = makeButton(R.drawable.grid_ic_open, R.string.grid_pick, v -> onPickClicked(i));
		s.btnMax = makeButton(R.drawable.grid_ic_fullscreen, R.string.grid_maximize, v -> toggleMax(i));
		for (ImageButton b : new ImageButton[]{s.btnRun, s.btnSettings, s.btnPick, s.btnMax}) {
			bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
		}
		s.cell.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
		return s;
	}

	private ImageButton makeButton(int icon, int description, View.OnClickListener listener) {
		ImageButton b = new ImageButton(this);
		b.setImageResource(icon);
		b.setBackgroundResource(R.drawable.grid_btn_bg);
		b.setScaleType(ImageButton.ScaleType.CENTER);
		b.setContentDescription(getString(description));
		b.setOnClickListener(listener);
		return b;
	}

	private static void setEnabledLook(View v, boolean enabled) {
		v.setEnabled(enabled);
		v.setAlpha(enabled ? 1f : 0.35f);
	}

	private void updateSlotUi(Slot s) {
		boolean has = s.path != null;
		String text;
		if (s.converting) {
			text = getString(R.string.grid_converting);
		} else if (!has) {
			text = getString(R.string.grid_empty, s.index + 1);
		} else {
			text = s.name + "\n" + getString(s.running ? R.string.grid_running : R.string.grid_stopped);
		}
		s.label.setText(text);
		s.btnRun.setImageResource(s.running ? R.drawable.grid_ic_stop : R.drawable.grid_ic_play);
		s.btnRun.setContentDescription(getString(s.running ? R.string.grid_stop : R.string.grid_run));
		boolean max = maxSlot == s.index;
		s.btnMax.setImageResource(max ? R.drawable.grid_ic_fullscreen_exit : R.drawable.grid_ic_fullscreen);
		s.btnMax.setContentDescription(getString(max ? R.string.grid_restore : R.string.grid_maximize));
		setEnabledLook(s.btnRun, has && !s.converting);
		// settings and file picking are locked while the game runs
		setEnabledLook(s.btnSettings, has && !s.converting && !s.running);
		setEnabledLook(s.btnPick, !anyConverting() && !s.running);
	}

	/** Lays the cells out in a 2x2 grid, or gives the whole area to one of them. */
	private static final class SlotGridLayout extends ViewGroup {
		private int maxIndex = -1;

		SlotGridLayout(Context context) {
			super(context);
		}

		void setMaxIndex(int index) {
			maxIndex = index;
			for (int i = 0; i < getChildCount(); i++) {
				getChildAt(i).setVisibility(index < 0 || index == i ? VISIBLE : GONE);
			}
			requestLayout();
		}

		@Override
		protected void onMeasure(int widthSpec, int heightSpec) {
			int w = MeasureSpec.getSize(widthSpec);
			int h = MeasureSpec.getSize(heightSpec);
			setMeasuredDimension(w, h);
			for (int i = 0; i < getChildCount(); i++) {
				View c = getChildAt(i);
				if (c.getVisibility() == GONE) {
					continue;
				}
				int cw = maxIndex < 0 ? w / 2 : w;
				int ch = maxIndex < 0 ? h / 2 : h;
				c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
						MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
			}
		}

		@Override
		protected void onLayout(boolean changed, int l, int t, int r, int b) {
			int w = r - l;
			int h = b - t;
			for (int i = 0; i < getChildCount(); i++) {
				View c = getChildAt(i);
				if (c.getVisibility() == GONE) {
					continue;
				}
				if (maxIndex < 0) {
					int col = i % 2;
					int row = i / 2;
					c.layout(col * w / 2, row * h / 2, (col + 1) * w / 2, (row + 1) * h / 2);
				} else {
					c.layout(0, 0, w, h);
				}
			}
		}
	}

	// ---------------------------------------------------------------- persistence / storage

	private void loadSlots() {
		for (Slot s : slots) {
			String path = slotPrefs.getString("path" + s.index, null);
			if (path != null && new File(path).isDirectory()) {
				s.path = path;
				s.name = slotPrefs.getString("name" + s.index, new File(path).getName());
			}
			updateSlotUi(s);
		}
	}

	private void saveSlot(Slot s) {
		slotPrefs.edit()
				.putString("path" + s.index, s.path)
				.putString("name" + s.index, s.name)
				.apply();
	}

	private void initStorage() {
		if (FileUtils.isExternalStorageLegacy()) {
			permissionLauncher.launch(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
		} else {
			initDirs();
		}
	}

	private void onPermissionResult(Map<String, Boolean> status) {
		if (Boolean.TRUE.equals(status.get(Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
			initDirs();
		} else {
			Toast.makeText(this, R.string.permission_request_failed, Toast.LENGTH_LONG).show();
		}
	}

	private void initDirs() {
		File dir = new File(Config.getEmulatorDir());
		if (!FileUtils.initWorkDir(dir)) {
			Toast.makeText(this, getString(R.string.create_apps_dir_failed, dir.getPath()),
					Toast.LENGTH_LONG).show();
		}
		//noinspection ResultOfMethodCallIgnored
		new File(Config.getProfilesDir()).mkdirs();
	}

	// ---------------------------------------------------------------- slot control

	private static Class<? extends Activity> slotClass(int i) {
		switch (i) {
			case 0:
				return SlotActivity0.class;
			case 1:
				return SlotActivity1.class;
			case 2:
				return SlotActivity2.class;
			default:
				return SlotActivity3.class;
		}
	}

	private boolean anyConverting() {
		for (Slot s : slots) {
			if (s != null && s.converting) {
				return true;
			}
		}
		return false;
	}

	private void updateAllSlotUi() {
		for (Slot s : slots) {
			updateSlotUi(s);
		}
	}

	private boolean anyRunning() {
		for (Slot s : slots) {
			if (s.running) {
				return true;
			}
		}
		return false;
	}

	private int modeFor(int index) {
		if (hideCount > 0) {
			return SlotProtocol.MODE_HIDDEN;
		}
		if (maxSlot < 0) {
			return SlotProtocol.MODE_GRID;
		}
		return maxSlot == index ? SlotProtocol.MODE_MAX : SlotProtocol.MODE_HIDDEN;
	}

	private void onRunClicked(int i) {
		Slot s = slots[i];
		if (s.running) {
			stopSlot(i);
		} else {
			startSlot(i);
		}
	}

	private void startSlot(int i) {
		Slot s = slots[i];
		if (s.running || s.converting) {
			return;
		}
		if (s.path == null) {
			Toast.makeText(this, R.string.grid_no_game, Toast.LENGTH_SHORT).show();
			return;
		}
		File config = new File(SlotPaths.configsDir(i), new File(s.path).getName());
		if (!config.exists()) {
			// first run of this game in this slot: show its settings first (like the original app)
			Toast.makeText(this, R.string.grid_first_settings, Toast.LENGTH_SHORT).show();
			openSettings(i);
			return;
		}
		int[] loc = new int[2];
		s.area.getLocationOnScreen(loc);
		int w = s.area.getWidth();
		int h = s.area.getHeight();
		int mode = modeFor(i);
		if (w <= 0 || h <= 0) {
			w = 1;
			h = 1;
			mode = SlotProtocol.MODE_HIDDEN;
		}
		Intent intent = new Intent(this, slotClass(i));
		intent.setData(Uri.parse(s.path));
		intent.putExtra(KEY_MIDLET_NAME, s.name);
		intent.putExtra(SlotProtocol.EXTRA_X, loc[0]);
		intent.putExtra(SlotProtocol.EXTRA_Y, loc[1]);
		intent.putExtra(SlotProtocol.EXTRA_W, w);
		intent.putExtra(SlotProtocol.EXTRA_H, h);
		intent.putExtra(SlotProtocol.EXTRA_MODE, mode);
		try {
			startActivity(intent);
			overridePendingTransition(0, 0);
		} catch (Exception e) {
			Log.e(TAG, "Can't start slot " + i, e);
			Toast.makeText(this, getString(R.string.grid_start_fail, e.getMessage()), Toast.LENGTH_LONG).show();
			return;
		}
		s.running = true;
		s.stopRequested = false;
		s.lastAlive = SystemClock.uptimeMillis();
		s.lastKey = "";
		updateSlotUi(s);
		// the slot process needs time to start: repeat the rectangle so it is surely received
		handler.postDelayed(() -> forcePush(s), 600);
		handler.postDelayed(() -> forcePush(s), 1800);
	}

	private void forcePush(Slot s) {
		if (s.running) {
			s.lastKey = "";
			sendLayout(s);
		}
	}

	private void stopSlot(int i) {
		Slot s = slots[i];
		if (!s.running) {
			return;
		}
		s.stopRequested = true;
		s.stopDeadline = SystemClock.uptimeMillis() + STOP_TIMEOUT;
		sendBroadcast(SlotProtocol.commandIntent(this, i, SlotProtocol.CMD_STOP));
	}

	private void markStopped(Slot s) {
		boolean wasRunning = s.running;
		s.running = false;
		s.stopRequested = false;
		s.stoppedAt = SystemClock.uptimeMillis();
		s.lastKey = "";
		if (maxSlot == s.index) {
			maxSlot = -1;
			grid.setMaxIndex(-1);
			for (Slot o : slots) {
				updateSlotUi(o);
			}
			schedulePush();
		}
		updateSlotUi(s);
		if (s.restartPending) {
			s.restartPending = false;
			startSlot(s.index);
		} else if (wasRunning) {
			schedulePush();
		}
	}

	private void toggleMax(int i) {
		maxSlot = maxSlot == i ? -1 : i;
		grid.setMaxIndex(maxSlot);
		for (Slot s : slots) {
			updateSlotUi(s);
		}
		// the new rectangles are sent after the layout pass (global layout listener)
		schedulePush();
	}

	private void schedulePush() {
		handler.removeCallbacks(pushRunnable);
		handler.post(pushRunnable);
	}

	private void pushLayout() {
		for (Slot s : slots) {
			if (s.running) {
				sendLayout(s);
			}
		}
	}

	private void sendLayout(Slot s) {
		int mode = modeFor(s.index);
		int w = s.area.getWidth();
		int h = s.area.getHeight();
		int x;
		int y;
		if (w <= 0 || h <= 0) {
			// cell is not shown (another one is maximized): keep the last rectangle, hide the window
			if (s.lastW <= 0) {
				return;
			}
			mode = SlotProtocol.MODE_HIDDEN;
			x = s.lastX;
			y = s.lastY;
			w = s.lastW;
			h = s.lastH;
		} else {
			int[] loc = new int[2];
			s.area.getLocationOnScreen(loc);
			x = loc[0];
			y = loc[1];
			s.lastX = x;
			s.lastY = y;
			s.lastW = w;
			s.lastH = h;
		}
		String key = x + "," + y + "," + w + "," + h + "," + mode;
		if (key.equals(s.lastKey)) {
			return;
		}
		s.lastKey = key;
		sendBroadcast(SlotProtocol.layoutIntent(this, s.index, x, y, w, h, mode));
	}

	/** Windows of the games are above this activity, so they must be hidden while a host dialog/menu is open. */
	private void beginHideSlots() {
		hideCount++;
		pushLayout();
	}

	private void endHideSlots() {
		if (hideCount > 0) {
			hideCount--;
		}
		pushLayout();
	}

	private void showDialog(AlertDialog.Builder builder) {
		AlertDialog dialog = builder.create();
		dialog.setOnDismissListener(d -> endHideSlots());
		beginHideSlots();
		dialog.show();
	}

	// ---------------------------------------------------------------- settings / pick / convert

	private void openSettings(int i) {
		Slot s = slots[i];
		if (s.path == null) {
			Toast.makeText(this, R.string.grid_no_game, Toast.LENGTH_SHORT).show();
			return;
		}
		Intent intent = new Intent(ACTION_EDIT, Uri.parse(s.path), this, ConfigActivity.class);
		intent.putExtra(KEY_MIDLET_NAME, s.name);
		intent.putExtra(KEY_SLOT, i);
		pendingConfigSlot = i;
		startActivity(new Intent(this, GridProxyActivity.class)
				.putExtra(GridProxyActivity.EXTRA_KIND, GridProxyActivity.KIND_CONFIG)
				.putExtra(GridProxyActivity.EXTRA_SLOT, i)
				.putExtra(GridProxyActivity.EXTRA_INTENT, intent));
		overridePendingTransition(0, 0);
	}

	private void onConfigResult(ActivityResult result) {
		int i = pendingConfigSlot;
		pendingConfigSlot = -1;
		Intent data = result.getData();
		if (i < 0 || result.getResultCode() != RESULT_OK || data == null
				|| !data.getBooleanExtra(KEY_SLOT_START, false)) {
			return;
		}
		Slot s = slots[i];
		if (s.running) {
			s.restartPending = true;
			stopSlot(i);
		} else {
			startSlot(i);
		}
	}

	private void onPickClicked(int i) {
		if (anyConverting()) {
			return;
		}
		pendingPickSlot = i;
		startActivity(new Intent(this, GridProxyActivity.class)
				.putExtra(GridProxyActivity.EXTRA_KIND, GridProxyActivity.KIND_PICK)
				.putExtra(GridProxyActivity.EXTRA_SLOT, i)
				.putExtra(GridProxyActivity.EXTRA_START_PATH, appPrefs.getString(PREF_LAST_PATH, null)));
		overridePendingTransition(0, 0);
	}

	private void onProxyResult(int index, Intent intent) {
		int kind = intent.getIntExtra(GridProxyActivity.EXTRA_KIND, 0);
		if (kind == GridProxyActivity.KIND_PICK) {
			String path = intent.getStringExtra(SlotProtocol.EXTRA_PATH);
			if (path != null) {
				installJar(index, Uri.fromFile(new File(path)));
			}
		} else if (kind == GridProxyActivity.KIND_CONFIG && intent.getBooleanExtra(KEY_SLOT_START, false)) {
			Slot s = slots[index];
			if (s.running) {
				s.restartPending = true;
				stopSlot(index);
			} else {
				startSlot(index);
			}
		}
	}

	private void onPicked(Uri uri) {
		int i = pendingPickSlot;
		pendingPickSlot = -1;
		if (uri == null || i < 0) {
			return;
		}
		installJar(i, uri);
	}

	private void handleViewIntent(Intent intent) {
		if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null
				|| (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
			return;
		}
		final Uri uri = intent.getData();
		intent.setAction(Intent.ACTION_MAIN);
		String[] names = new String[slots.length];
		for (int i = 0; i < names.length; i++) {
			names[i] = getString(R.string.grid_slot_name, i + 1);
			if (slots[i].name != null) {
				names[i] += " - " + slots[i].name;
			}
		}
		showDialog(new AlertDialog.Builder(this)
				.setTitle(R.string.grid_pick_slot_title)
				.setItems(names, (d, which) -> installJar(which, uri))
				.setNegativeButton(android.R.string.cancel, null));
	}

	private void installJar(final int index, final Uri uri) {
		final Slot s = slots[index];
		if (anyConverting()) {
			return;
		}
		s.converting = true;
		updateAllSlotUi();
		if (s.running) {
			s.restartPending = false;
			stopSlot(index);
		}
		final Context appContext = getApplicationContext();
		Thread worker = new Thread(null, () -> {
			SlotInstaller.Result result = null;
			String error = null;
			try {
				for (int k = 0; k < 60 && s.running; k++) {
					Thread.sleep(100);
				}
				if (s.running) {
					throw new IOException("Không dừng được game đang chạy ở lưới này");
				}
				result = SlotInstaller.install(appContext, index, uri);
			} catch (Throwable e) {
				Log.e(TAG, "Convert failed", e);
				error = e.getMessage() != null ? e.getMessage() : e.toString();
			}
			final SlotInstaller.Result r = result;
			final String err = error;
			handler.post(() -> onInstallDone(s, r, err));
		}, "SlotInstall", 16L * 1024 * 1024);
		worker.start();
	}

	private void onInstallDone(Slot s, SlotInstaller.Result result, String error) {
		s.converting = false;
		if (result != null) {
			s.name = result.name;
			s.path = result.path;
			saveSlot(s);
			Toast.makeText(this, getString(R.string.grid_convert_ok, result.name), Toast.LENGTH_LONG).show();
		} else {
			Toast.makeText(this, getString(R.string.grid_convert_fail, error), Toast.LENGTH_LONG).show();
		}
		updateAllSlotUi();
	}

	// ---------------------------------------------------------------- menu

	private void showMenu(View anchor) {
		PopupMenu popup = new PopupMenu(this, anchor);
		Menu menu = popup.getMenu();
		menu.add(0, 1, 0, R.string.grid_menu_default_settings);
		menu.add(0, 2, 1, R.string.grid_menu_app_settings);
		menu.add(0, 3, 2, R.string.grid_menu_classic);
		menu.add(0, 4, 3, R.string.grid_menu_about);
		menu.add(0, 5, 4, R.string.grid_menu_stop_all);
		menu.add(0, 6, 5, R.string.grid_menu_exit);
		popup.setOnMenuItemClickListener(item -> {
			onMenuItem(item.getItemId());
			return true;
		});
		popup.setOnDismissListener(p -> endHideSlots());
		beginHideSlots();
		popup.show();
	}

	private void onMenuItem(int id) {
		switch (id) {
			case 1:
				startActivity(new Intent(this, ProfilesActivity.class));
				break;
			case 2:
				startActivity(new Intent(this, SettingsActivity.class));
				break;
			case 3:
				startActivity(new Intent(this, MainActivity.class));
				break;
			case 4:
				showDialog(new AlertDialog.Builder(this)
						.setTitle(R.string.grid_app_title)
						.setMessage(R.string.grid_about_text)
						.setPositiveButton(android.R.string.ok, null));
				break;
			case 5:
				for (Slot s : slots) {
					stopSlot(s.index);
				}
				break;
			case 6:
				for (Slot s : slots) {
					stopSlot(s.index);
				}
				if (android.os.Build.VERSION.SDK_INT >= 21) {
					finishAndRemoveTask();
				} else {
					finish();
				}
				break;
			default:
				break;
		}
	}
}
