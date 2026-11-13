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

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.android.dx.command.dexer.Main;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.jar.JarFile;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.ZipUtils;
import ru.woesss.j2me.jar.Descriptor;

/**
 * Converts a .jar (dex + res.jar + manifest) into the converted/ dir of one slot.
 * Simplified version of AppInstaller that does not touch the apps database.
 */
public final class SlotInstaller {
	private static final String TAG = SlotInstaller.class.getSimpleName();
	/** dx is not re-entrant, so only one conversion at a time. */
	private static final Object LOCK = new Object();

	public static final class Result {
		public final String dirName;
		public final String name;
		public final String path;

		Result(String dirName, String name, String path) {
			this.dirName = dirName;
			this.name = name;
			this.path = path;
		}
	}

	private SlotInstaller() {
	}

	/** Blocking. Must be called from a worker thread (preferably with a big stack). */
	public static Result install(Context context, int slot, Uri uri) throws IOException {
		synchronized (LOCK) {
			return doInstall(context, slot, uri);
		}
	}

	/** Blocking. Same as above for a local file. */
	public static Result install(Context context, int slot, File file) throws IOException {
		synchronized (LOCK) {
			return doInstall(slot, file);
		}
	}

	private static Result doInstall(Context context, int slot, Uri uri) throws IOException {
		return doInstall(slot, FileUtils.getFileForUri(context, uri));
	}

	private static Result doInstall(int slot, File src) throws IOException {
		if (!isZip(src)) {
			throw new IOException("Không phải file JAR hợp lệ: " + src.getName());
		}
		Descriptor desc = loadManifest(src);
		if (desc.getAttrs().get("MIDlet-1") == null) {
			throw new IOException("MANIFEST của JAR thiếu thuộc tính MIDlet-1 (game này cần kèm file .jad)");
		}
		String name = desc.getName();
		if (name == null || name.trim().isEmpty()) {
			name = stripExt(src.getName());
		}
		name = name.trim();
		String dirName = name.replaceAll(FileUtils.ILLEGAL_FILENAME_CHARS, "").trim();
		if (dirName.isEmpty() || dirName.equals(".") || dirName.equals("..")) {
			dirName = "game";
		}

		File convertedDir = SlotPaths.convertedDir(slot);
		File tmpDir = new File(convertedDir, ".tmp");
		if (tmpDir.exists()) {
			FileUtils.deleteDirectory(tmpDir);
		}
		if (!tmpDir.mkdirs()) {
			throw new IOException("Không tạo được thư mục: " + tmpDir);
		}
		try {
			Main.main(new String[]{"--no-optimize", "--core-library",
					"--output=" + tmpDir + Config.MIDLET_DEX_FILE,
					src.getAbsolutePath()});
		} catch (Throwable e) {
			FileUtils.deleteDirectory(tmpDir);
			throw new IOException("Lỗi convert (dex): " + e, e);
		}
		File resJar = new File(tmpDir, Config.MIDLET_RES_FILE);
		FileUtils.copyFileUsingChannel(src, resJar);
		String icon = null;
		try {
			icon = desc.getIcon();
		} catch (Exception e) {
			Log.w(TAG, "Bad icon attribute", e);
		}
		if (icon != null) {
			File iconFile = new File(tmpDir, Config.MIDLET_ICON_FILE);
			try {
				ZipUtils.unzipEntry(resJar, icon, iconFile);
			} catch (Exception e) {
				Log.w(TAG, "Can't unzip icon: " + icon, e);
				//noinspection ResultOfMethodCallIgnored
				iconFile.delete();
			}
		}
		desc.writeTo(new File(tmpDir, Config.MIDLET_MANIFEST_FILE));

		File target = new File(convertedDir, dirName);
		if (target.exists()) {
			FileUtils.deleteDirectory(target);
		}
		if (!tmpDir.renameTo(target)) {
			throw new IOException("Không di chuyển được '" + tmpDir + "' sang '" + target + "'");
		}
		// a slot holds one game: drop previously converted games (their data/configs are kept)
		File[] others = convertedDir.listFiles();
		if (others != null) {
			for (File other : others) {
				if (!other.equals(target)) {
					FileUtils.deleteDirectory(other);
				}
			}
		}
		return new Result(dirName, name, target.getAbsolutePath());
	}

	private static boolean isZip(File file) {
		try (FileInputStream in = new FileInputStream(file)) {
			return in.read() == 0x50 && in.read() == 0x4B;
		} catch (IOException e) {
			return false;
		}
	}

	private static String stripExt(String fileName) {
		int i = fileName.lastIndexOf('.');
		return i > 0 ? fileName.substring(0, i) : fileName;
	}

	private static Descriptor loadManifest(File jar) throws IOException {
		ZipFile zip = new ZipFile(jar);
		FileHeader header = zip.getFileHeader(JarFile.MANIFEST_NAME);
		if (header == null) {
			throw new IOException("JAR không có " + JarFile.MANIFEST_NAME);
		}
		try (ZipInputStream is = zip.getInputStream(header)) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream(20480);
			byte[] buf = new byte[4096];
			int read;
			while ((read = is.read(buf)) != -1) {
				baos.write(buf, 0, read);
			}
			return new Descriptor(baos.toString(), false);
		}
	}
}
