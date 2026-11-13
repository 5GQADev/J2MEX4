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

import java.io.File;

import ru.playsoftware.j2meloader.config.Config;

/**
 * Directory layout of a slot. Every slot is a complete, independent "emulator dir":
 * <pre>
 * [emulatorDir]/slots/slotN/converted/[game]   converted game (dex, res.jar, manifest)
 * [emulatorDir]/slots/slotN/configs/[game]     per-slot settings of the game
 * [emulatorDir]/slots/slotN/data/[game]        per-slot RMS / game data
 * [emulatorDir]/slots/slotN/cache              per-slot temp dir
 * </pre>
 * The emulator core derives configs/ and data/ from the parent of converted/, so a game
 * started from converted/ of a slot automatically uses the data of that slot only.
 */
public final class SlotPaths {
	private SlotPaths() {
	}

	public static File slotDir(int slot) {
		return new File(Config.getEmulatorDir() + "/slots/slot" + slot);
	}

	public static File convertedDir(int slot) {
		return new File(slotDir(slot), "converted");
	}

	public static File cacheDir(int slot) {
		return new File(slotDir(slot), "cache");
	}

	public static File dataDir(int slot) {
		return new File(slotDir(slot), "data");
	}

	public static File configsDir(int slot) {
		return new File(slotDir(slot), "configs");
	}
}
