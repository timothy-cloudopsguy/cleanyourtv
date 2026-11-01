package io.github.timothy_cloudopsguy.opsdeck;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.List;
import java.util.Locale;

/** This TV: memory, storage, live network use, how each feed is doing, and the sound switch. */
final class SystemPanel extends Panel {
    private static final float SPLIT = 1020f;
    private static final int HISTORY = 90;
    private static final int FEED_ROWS = 33;
    private static final long GB = 1024L * 1024 * 1024;

    private final ActivityManager activityManager;
    private final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
    private final float[] rx = new float[HISTORY];
    private final float[] tx = new float[HISTORY];
    private final Path path = new Path();
    private int samples;
    private long lastSample;
    private long lastRx = -1;
    private long lastTx;
    private long storageFree;
    private long storageTotal;

    SystemPanel(Deck deck, Context context) {
        super(deck);
        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    @Override String railName() { return "SYSTEM"; }
    @Override String title() { return "THIS TV"; }
    @Override int accent() { return Ui.MAGENTA; }

    @Override
    boolean key(int key) {
        if (Ui.isOk(key)) {
            deck.sfx.toggle();
            return true;
        }
        return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    void draw(Canvas c, long now) {
        if (now - lastSample >= 1000) sample(now);
        float t = DeckView.Y0;
        Ui.card(c, DeckView.X0, t, SPLIT, t + 150, Ui.PANEL);
        Ui.bold(c, (Build.MANUFACTURER + " " + Build.MODEL).toUpperCase(Locale.ROOT), DeckView.X0 + 24, t + 52, 32, Ui.TEXT);
        Ui.mono(c, "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT, DeckView.X0 + 24, t + 90, 24, Ui.DIM);
        Ui.mono(c, "Up " + uptime() + "   " + deck.apps.size() + " TV apps", DeckView.X0 + 24, t + 126, 24, Ui.DIM);

        float m = t + 168;
        Ui.card(c, DeckView.X0, m, SPLIT, m + 220, Ui.PANEL);
        float used = 1 - memory.availMem / (float) Math.max(1, memory.totalMem);
        Ui.heading(c, "MEMORY", DeckView.X0 + 24, m + 42);
        Ui.right(c, gb(memory.totalMem - memory.availMem) + " / " + gb(memory.totalMem), SPLIT - 24, m + 42, 25, Ui.TEXT);
        Ui.bar(c, DeckView.X0 + 24, m + 60, SPLIT - 24, m + 84, used, used > 0.85f ? Ui.RED : Ui.CYAN);
        float usedDisk = 1 - storageFree / (float) Math.max(1, storageTotal);
        Ui.heading(c, "STORAGE", DeckView.X0 + 24, m + 136);
        Ui.right(c, gb(storageTotal - storageFree) + " / " + gb(storageTotal), SPLIT - 24, m + 136, 25, Ui.TEXT);
        Ui.bar(c, DeckView.X0 + 24, m + 154, SPLIT - 24, m + 178, usedDisk, usedDisk > 0.9f ? Ui.RED : Ui.MAGENTA);

        drawNetwork(c, m + 238);
        drawSound(c, now);
        drawFeeds(c, now);
    }

    private void drawNetwork(Canvas c, float t) {
        float b = t + 250;
        Ui.card(c, DeckView.X0, t, SPLIT, b, Ui.PANEL);
        Ui.heading(c, "NETWORK", DeckView.X0 + 24, t + 42);
        float lastDown = samples > 0 ? rx[(samples - 1) % HISTORY] : 0;
        float lastUp = samples > 0 ? tx[(samples - 1) % HISTORY] : 0;
        Ui.right(c, "DOWN " + rate(lastDown), SPLIT - 240, t + 42, 22, Ui.CYAN);
        Ui.right(c, "UP " + rate(lastUp), SPLIT - 24, t + 42, 22, Ui.AMBER);
        float max = 16 * 1024;
        for (int i = 0; i < Math.min(samples, HISTORY); i++) max = Math.max(max, Math.max(rx[i], tx[i]));
        plot(c, rx, max, t + 60, b - 18, Ui.CYAN);
        plot(c, tx, max, t + 60, b - 18, Ui.AMBER);
    }

    private void plot(Canvas c, float[] values, float max, float t, float b, int color) {
        int n = Math.min(samples, HISTORY);
        if (n < 2) return;
        float l = DeckView.X0 + 24;
        float r = SPLIT - 24;
        path.rewind();
        for (int i = 0; i < n; i++) {
            float value = values[(samples - n + i) % HISTORY];
            float x = l + (r - l) * i / (HISTORY - 1);
            float y = b - (b - t) * value / max;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        Ui.STROKE.setColor(color);
        Ui.STROKE.setStrokeWidth(3);
        c.drawPath(path, Ui.STROKE);
    }

    private void drawSound(Canvas c, long now) {
        float t = DeckView.Y0 + 674;
        float b = DeckView.Y1;
        Ui.card(c, DeckView.X0, t, SPLIT, b, Ui.PANEL_HI);
        boolean on = deck.sfx.isOn();
        Ui.bold(c, "SOUND EFFECTS  " + (on ? "ON" : "OFF"), DeckView.X0 + 24, t + 62, 34, on ? Ui.GREEN : Ui.FAINT);
        Ui.mono(c, "Press OK to switch", DeckView.X0 + 24, t + 104, 24, Ui.DIM);
        if (focused) Ui.focus(c, DeckView.X0, t, SPLIT, b, Ui.MAGENTA, now);
    }

    private void drawFeeds(Canvas c, long now) {
        float l = SPLIT + 22;
        float r = DeckView.X1;
        Ui.card(c, l, DeckView.Y0, r, DeckView.Y1, Ui.PANEL);
        Ui.heading(c, "FEED HEALTH", l + 24, DeckView.Y0 + 42);
        List<Store.Job> jobs = deck.store.jobs;
        float columnW = (r - l - 24) / 2;
        for (int i = 0; i < Math.min(FEED_ROWS * 2, jobs.size()); i++) {
            Store.Job job = jobs.get(i);
            float y = DeckView.Y0 + 72 + (i % FEED_ROWS) * 26.5f;
            float left = l + (i / FEED_ROWS) * columnW;
            int color = !job.error.isEmpty() ? Ui.RED : job.lastGood > 0 ? Ui.GREEN : Ui.FAINT;
            Ui.FILL.setColor(color);
            c.drawCircle(left + 32, y - 7, 7, Ui.FILL);
            Ui.line(c, job.name, left + 52, y, columnW - 150, 19, Ui.TEXT, Ui.MONO);
            String state = !job.error.isEmpty() ? job.error : job.lastGood > 0 ? Parsers.ago(job.lastGood, now) + " ago" : "waiting";
            Ui.right(c, state, left + columnW - 4, y, 18, color == Ui.GREEN ? Ui.DIM : color);
        }
    }

    private void sample(long now) {
        activityManager.getMemoryInfo(memory);
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        storageFree = stat.getAvailableBytes();
        storageTotal = stat.getTotalBytes();
        long down = TrafficStats.getTotalRxBytes();
        long up = TrafficStats.getTotalTxBytes();
        // Unsupported returns -1: the chart stays flat rather than showing nonsense.
        if (lastRx >= 0 && down >= 0 && up >= 0 && lastSample > 0) {
            float seconds = Math.max(0.5f, (now - lastSample) / 1000f);
            rx[samples % HISTORY] = Math.max(0, (down - lastRx) / seconds);
            tx[samples % HISTORY] = Math.max(0, (up - lastTx) / seconds);
            samples++;
        }
        lastRx = down;
        lastTx = up;
        lastSample = now;
    }

    private static String uptime() {
        long minutes = SystemClock.elapsedRealtime() / 60_000;
        return minutes / 1440 + "d " + (minutes / 60) % 24 + "h " + minutes % 60 + "m";
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / (float) GB);
    }

    private static String rate(float bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024 * 1024));
        return String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024);
    }
}
