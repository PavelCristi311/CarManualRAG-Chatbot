package com.atlas.manualassistant;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class AssetInstaller {
    private static final int BUFFER_SIZE = 1024 * 1024;

    private AssetInstaller() {}

    /**
     * Atomically installs a bundled asset when the validated local copy is absent.
     */
    static File ensureFile(
            Context context,
            String assetPath,
            String fileName,
            long expectedBytes) throws IOException {
        File destination = new File(context.getNoBackupFilesDir(), fileName);
        if (destination.isFile()
                && (expectedBytes <= 0 || destination.length() == expectedBytes)) {
            return destination;
        }
        File temporary = new File(destination.getParentFile(), fileName + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Cannot replace temporary asset " + temporary);
        }
        AssetManager assets = context.getAssets();
        try (InputStream input =
                     new BufferedInputStream(assets.open(assetPath), BUFFER_SIZE);
             BufferedOutputStream output =
                     new BufferedOutputStream(new FileOutputStream(temporary), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        if (expectedBytes > 0 && temporary.length() != expectedBytes) {
            temporary.delete();
            throw new IOException("Bundled asset size mismatch: " + assetPath);
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace " + destination);
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Cannot install " + destination);
        }
        return destination;
    }
}
