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

import static ru.playsoftware.j2meloader.util.Constants.KEY_SLOT_START;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import ru.playsoftware.j2meloader.util.FileUtils;

/**
 * Invisible helper that receives results of the file picker / settings screen.
 * The grid activity lies below the game windows and is not resumed when these screens close,
 * so it would receive their results too late. This activity is on top, gets the result at once
 * and passes it to the grid by broadcast.
 */
public class GridProxyActivity extends AppCompatActivity {
	public static final String EXTRA_KIND = "kind";
	public static final String EXTRA_SLOT = "slot";
	public static final String EXTRA_INTENT = "intent";
	public static final String EXTRA_START_PATH = "startPath";
	public static final int KIND_PICK = 1;
	public static final int KIND_CONFIG = 2;

	private int slot;
	private int kind;

	private final ActivityResultLauncher<String> pickLauncher =
			registerForActivityResult(FileUtils.getFilePicker(), this::onPicked);
	private final ActivityResultLauncher<Intent> configLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), this::onConfig);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		slot = intent.getIntExtra(EXTRA_SLOT, -1);
		kind = intent.getIntExtra(EXTRA_KIND, 0);
		if (savedInstanceState != null) {
			return; // result is delivered to the launchers registered above
		}
		if (kind == KIND_PICK) {
			pickLauncher.launch(intent.getStringExtra(EXTRA_START_PATH));
		} else if (kind == KIND_CONFIG) {
			Intent target = intent.getParcelableExtra(EXTRA_INTENT);
			if (target == null) {
				finish();
				return;
			}
			configLauncher.launch(target);
		} else {
			finish();
		}
	}

	private void onPicked(Uri uri) {
		if (uri != null) {
			try {
				// copy now: the permission to read the uri ends with this activity
				File file = FileUtils.getFileForUri(getApplicationContext(), uri);
				sendBroadcast(result().putExtra(SlotProtocol.EXTRA_PATH, file.getAbsolutePath()));
			} catch (Exception e) {
				Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
			}
		}
		finish();
		overridePendingTransition(0, 0);
	}

	private void onConfig(ActivityResult r) {
		Intent data = r.getData();
		boolean start = r.getResultCode() == RESULT_OK && data != null
				&& data.getBooleanExtra(KEY_SLOT_START, false);
		sendBroadcast(result().putExtra(KEY_SLOT_START, start));
		finish();
		overridePendingTransition(0, 0);
	}

	private Intent result() {
		return SlotProtocol.eventIntent(this, slot, SlotProtocol.EVT_PROXY_RESULT)
				.putExtra(EXTRA_KIND, kind);
	}
}
