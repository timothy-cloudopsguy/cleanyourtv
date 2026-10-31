package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.LruCache;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** Colours and small drawing helpers. All drawing is in a 1920x1080 space; DeckView scales it to the screen. */
final class Ui {
    static final int BG = 0xFF060A12;
    static final int PANEL = 0xFF0D1524;
    static final int PANEL_HI = 0xFF16243A;
    static final int LINE = 0xFF1F3048;
    static final int TEXT = 0xFFE8F1FA;
    static final int DIM = 0xFF8CA0B8;
    static final int FAINT = 0xFF56677E;
    static final int CYAN = 0xFF22E6C3;
    static final int AMBER = 0xFFFFB84D;
    static final int RED = 0xFFFF4D6D;
    static final int MAGENTA = 0xFFD97CFF;
    static final int BLUE = 0xFF5AB4FF;
    static final int GREEN = 0xFF4CE28A;

    static final Typeface MONO = Typeface.MONOSPACE;
    static final Typeface MONO_BOLD = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    static final Typeface SANS = Typeface.DEFAULT;

    static final Paint TXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final RectF R = new RectF();

    private static final LruCache<String, String[]> WRAPPED = new LruCache<>(256);

    static {
        STROKE.setStyle(Paint.Style.STROKE);
    }

    private Ui() {}

    static void mono(Canvas c, String s, float x, float y, float size, int color) {
        TXT.setTypeface(MONO);
        TXT.setTextAlign(Paint.Align.LEFT);
        TXT.setTextSize(size);
        TXT.setColor(color);
        c.drawText(s, x, y, TXT);
    }

    static void bold(Canvas c, String s, float x, float y, float size, int color) {
        TXT.setTypeface(MONO_BOLD);
        TXT.setTextAlign(Paint.Align.LEFT);
        TXT.setTextSize(size);
        TXT.setColor(color);
        c.drawText(s, x, y, TXT);
    }

    static void right(Canvas c, String s, float x, float y, float size, int color) {
        TXT.setTypeface(MONO);
        TXT.setTextAlign(Paint.Align.RIGHT);
        TXT.setTextSize(size);
        TXT.setColor(color);
        c.drawText(s, x, y, TXT);
        TXT.setTextAlign(Paint.Align.LEFT);
    }

    static void centre(Canvas c, String s, float x, float y, float size, int color) {
        TXT.setTypeface(MONO_BOLD);
        TXT.setTextAlign(Paint.Align.CENTER);
        TXT.setTextSize(size);
        TXT.setColor(color);
        c.drawText(s, x, y, TXT);
        TXT.setTextAlign(Paint.Align.LEFT);
    }

    /** One line, cut with an ellipsis to fit the width. */
    static void line(Canvas c, String s, float x, float y, float width, float size, int color, Typeface face) {
        String[] lines = wrap(s, width, size, face, 1);
        if (lines.length == 0) return;
        TXT.setTypeface(face);
        TXT.setTextAlign(Paint.Align.LEFT);
        TXT.setTextSize(size);
        TXT.setColor(color);
        c.drawText(lines[0], x, y, TXT);
    }

    /** Word-wrapped text; returns the y below the last line. */
    static float para(Canvas c, String s, float x, float y, float width, float size, int color, Typeface face, int maxLines) {
        String[] lines = wrap(s, width, size, face, maxLines);
        TXT.setTypeface(face);
        TXT.setTextAlign(Paint.Align.LEFT);
        TXT.setTextSize(size);
        TXT.setColor(color);
        float step = size * 1.32f;
        for (String l : lines) {
            c.drawText(l, x, y, TXT);
            y += step;
        }
        return y;
    }

    static String[] wrap(String s, float width, float size, Typeface face, int maxLines) {
        String key = s + '|' + (int) width + '|' + (int) size + '|' + maxLines + '|' + (face == SANS);
        String[] cached = WRAPPED.get(key);
        if (cached != null) return cached;
        Paint p = new Paint();
        p.setTypeface(face);
        p.setTextSize(size);
        List<String> out = new ArrayList<>();
        String rest = s.trim().replace('\n', ' ');
        while (!rest.isEmpty() && out.size() < maxLines) {
            int n = p.breakText(rest, true, width, null);
            if (n >= rest.length()) {
                out.add(rest);
                rest = "";
                break;
            }
            int cut = rest.lastIndexOf(' ', n);
            if (cut <= 0) cut = n;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty() && !out.isEmpty()) {
            String last = out.get(out.size() - 1);
            int n = p.breakText(last + "...", true, width, null);
            if (n < last.length() + 3) last = last.substring(0, Math.max(0, n - 3)).trim();
            out.set(out.size() - 1, last + "...");
        }
        String[] result = out.toArray(new String[0]);
        WRAPPED.put(key, result);
        return result;
    }

    static void card(Canvas c, float l, float t, float r, float b, int fill) {
        R.set(l, t, r, b);
        FILL.setColor(fill);
        c.drawRoundRect(R, 10, 10, FILL);
    }

    /** A focus ring that breathes a little, so the eye finds it across the room. */
    static void focus(Canvas c, float l, float t, float r, float b, int color, long now) {
        float pulse = (float) (Math.sin(now / 220.0) * 0.5 + 0.5);
        STROKE.setColor(color);
        STROKE.setStrokeWidth(3 + pulse * 2);
        R.set(l - 3, t - 3, r + 3, b + 3);
        c.drawRoundRect(R, 12, 12, STROKE);
        STROKE.setAlpha(50);
        STROKE.setStrokeWidth(10);
        R.set(l - 8, t - 8, r + 8, b + 8);
        c.drawRoundRect(R, 16, 16, STROKE);
    }

    static void bar(Canvas c, float l, float t, float r, float b, float fraction, int color) {
        card(c, l, t, r, b, LINE);
        card(c, l, t, l + (r - l) * Math.max(0, Math.min(1, fraction)), b, color);
    }

    static void heading(Canvas c, String s, float x, float y) {
        bold(c, s, x, y, 24, CYAN);
    }

    static void qr(Canvas c, boolean[][] modules, float x, float y, float side) {
        card(c, x, y, x + side, y + side, TEXT);
        int quiet = 2;
        int n = modules.length + 2 * quiet;
        float cell = (float) Math.floor(side / n);
        float offset = (side - cell * n) / 2 + quiet * cell;
        FILL.setColor(BG);
        FILL.setAntiAlias(false);
        for (int row = 0; row < modules.length; row++) {
            for (int col = 0; col < modules.length; col++) {
                if (modules[row][col]) {
                    float px = x + offset + col * cell;
                    float py = y + offset + row * cell;
                    c.drawRect(px, py, px + cell, py + cell, FILL);
                }
            }
        }
        FILL.setAntiAlias(true);
    }

    static boolean isOk(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A;
    }

    /** The colour for a Statuspage indicator: none, minor, major, critical, maintenance. */
    static int statusColor(String indicator) {
        switch (indicator) {
            case "none": return GREEN;
            case "minor": return AMBER;
            case "maintenance": return BLUE;
            case "major":
            case "critical": return RED;
            default: return FAINT;
        }
    }

    static String number(int n) {
        return String.format(java.util.Locale.US, "%,d", n);
    }
}
