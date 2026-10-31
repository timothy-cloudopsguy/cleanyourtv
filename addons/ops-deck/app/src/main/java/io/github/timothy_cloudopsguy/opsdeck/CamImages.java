package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera pictures, fetched only when a panel asks while drawing, so nothing refreshes once the screen is gone. Some
 * observatory images are nearly 2 MB at full size, so they are decoded down to about what a wall tile needs.
 */
final class CamImages {
    private static final int TARGET_WIDTH = 960;
    private static final int MAX_IN_MEMORY = 10;
    private static final long RETRY_MS = 2 * 60_000;

    private final File dir;
    private final ExecutorService threads = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> images = new LruCache<>(MAX_IN_MEMORY);
    private final Map<String, Long> fetched = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Long> failed = Collections.synchronizedMap(new HashMap<>());
    private final Set<String> busy = Collections.synchronizedSet(new HashSet<>());

    CamImages(Context context) {
        dir = new File(context.getCacheDir(), "cams");
        dir.mkdirs();
    }

    /** The newest picture on hand; asks for a fresh one in the background when it is due. */
    Bitmap get(VolcanoData.Cam cam, long now) {
        Long at = fetched.get(cam.id);
        Long gaveUp = failed.get(cam.id);
        Bitmap image = images.get(cam.id);
        boolean due = image == null || at == null || now - at >= cam.refreshMs;
        boolean waiting = gaveUp != null && now - gaveUp < RETRY_MS;
        if (due && !waiting && busy.add(cam.id)) threads.execute(() -> load(cam));
        return image;
    }

    /** When the picture on screen was fetched; 0 before the first one. */
    long fetchedAt(String id) {
        Long at = fetched.get(id);
        return at == null ? 0 : at;
    }

    boolean failing(String id) {
        return failed.containsKey(id);
    }

    private void load(VolcanoData.Cam cam) {
        File file = new File(dir, cam.id + ".jpg");
        try {
            long now = System.currentTimeMillis();
            // A reopen shortly after the last visit reuses the file, so each opening does not fetch everything again.
            if (!file.isFile() || now - file.lastModified() >= cam.refreshMs) Store.download(cam.imageUrl, file);
            Bitmap bitmap = decode(file);
            if (bitmap == null) throw new IOException("not an image");
            images.put(cam.id, bitmap);
            fetched.put(cam.id, file.lastModified());
            failed.remove(cam.id);
        } catch (IOException e) {
            failed.put(cam.id, System.currentTimeMillis());
        } finally {
            busy.remove(cam.id);
        }
    }

    private static Bitmap decode(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        int sample = 1;
        while (options.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }
}
