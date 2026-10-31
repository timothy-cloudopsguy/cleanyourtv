package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The whole screen, on one Canvas at about 30 frames a second: the rail on the left and the chosen panel beside it.
 * Keys go to the rail until Right or OK moves into the panel; a panel gives them back when it has nowhere left to go.
 */
final class DeckView extends View {
    static final float W = 1920f;
    static final float H = 1080f;
    static final float X0 = 270f;
    static final float X1 = 1872f;
    static final float Y0 = 118f;
    static final float Y1 = 1040f;
    private static final long FRAME_MS = 33;

    final Deck deck;
    private final Panel[] panels;
    private final Paint scanlines = new Paint();
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE dd MMM yyyy", Locale.getDefault());

    private int current;
    private boolean inRail;
    private boolean running;
    private long clockSecond;
    private String clock = "";
    private String date = "";
    private long fpsStart;
    private int fpsFrames;
    int fps;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    DeckView(Context context, Deck deck, Panel[] panels) {
        super(context);
        this.deck = deck;
        this.panels = panels;
        setFocusable(true);
        setFocusableInTouchMode(true);
        Bitmap line = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888);
        line.setPixel(0, 2, Color.argb(36, 0, 0, 0));
        scanlines.setShader(new BitmapShader(line, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    void resume() {
        running = true;
        removeCallbacks(frame);
        post(frame);
    }

    void pause() {
        running = false;
        removeCallbacks(frame);
        panels[current].onHidden();
    }

    void openPanel(int index) {
        if (index == current) return;
        panels[current].onHidden();
        current = index;
        panels[current].onShown();
    }

    /** Back: out of a panel to the rail, then home to the Bridge. False when there is nowhere left, so the app leaves. */
    boolean back() {
        if (!inRail && panels[current].back()) {
            deck.sfx.tick();
            return true;
        }
        if (!inRail) {
            inRail = true;
            panels[current].onHidden();
            deck.sfx.tick();
            return true;
        }
        if (current != 0) {
            openPanel(0);
            deck.sfx.tick();
            return true;
        }
        return false;
    }

    void enterPanel(int index) {
        openPanel(index);
        inRail = false;
        panels[current].onShown();
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_BACK) {
            return back() || super.onKeyDown(key, event);
        }
        if (inRail) return railKey(key) || super.onKeyDown(key, event);
        if (panels[current].key(key)) return true;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            inRail = true;
            panels[current].onHidden();
            deck.sfx.tick();
            return true;
        }
        return super.onKeyDown(key, event);
    }

    private boolean railKey(int key) {
        if (key == KeyEvent.KEYCODE_DPAD_UP && current > 0) {
            openPanel(current - 1);
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN && current < panels.length - 1) {
            openPanel(current + 1);
        } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT || Ui.isOk(key)) {
            inRail = false;
            panels[current].onShown();
        } else {
            return false;
        }
        deck.sfx.tick();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis();
        countFrame(now);
        c.drawColor(Ui.BG);
        int saved = c.save();
        c.scale(getWidth() / W, getHeight() / H);
        // A pixel or two of drift each minute, so a dashboard left on for hours does not mark a panel.
        long minute = now / 60_000;
        c.translate(minute % 5 - 2, (minute / 5) % 5 - 2);
        drawChrome(c, now);
        panels[current].focused = !inRail;
        panels[current].draw(c, now);
        c.restoreToCount(saved);
        c.drawRect(0, 0, getWidth(), getHeight(), scanlines);
    }

    private void countFrame(long now) {
        fpsFrames++;
        if (now - fpsStart >= 1000) {
            fps = fpsFrames;
            fpsFrames = 0;
            fpsStart = now;
        }
    }

    private void drawChrome(Canvas c, long now) {
        Ui.bold(c, "OPS", 48, 86, 44, Ui.CYAN);
        Ui.bold(c, "DECK", 134, 86, 44, Ui.TEXT);
        long second = now / 1000;
        if (second != clockSecond) {
            clockSecond = second;
            Date d = new Date(now);
            clock = clockFormat.format(d);
            date = dateFormat.format(d);
        }
        Ui.bold(c, panels[current].title(), X0, 80, 34, Ui.TEXT);
        Ui.right(c, date, 1620, 80, 24, Ui.DIM);
        TextRight.draw(c, clock, X1, 84, 42);
        Ui.FILL.setColor(Ui.LINE);
        c.drawRect(X0, 100, X1, 102, Ui.FILL);

        for (int i = 0; i < panels.length; i++) {
            float top = 140 + i * 92;
            boolean active = i == current;
            if (active) {
                Ui.card(c, 40, top, 240, top + 76, Ui.PANEL_HI);
                Ui.FILL.setColor(panels[i].accent());
                c.drawRect(40, top + 10, 46, top + 66, Ui.FILL);
                if (inRail) Ui.focus(c, 40, top, 240, top + 76, panels[i].accent(), now);
            }
            Ui.bold(c, panels[i].railName(), 62, top + 48, 25, active ? Ui.TEXT : Ui.DIM);
        }
        Ui.mono(c, deck.sfx.isOn() ? "SOUND ON" : "SOUND OFF", 50, 1000, 18, Ui.FAINT);
        Ui.mono(c, fps + " FPS", 50, 1028, 18, Ui.FAINT);
    }

    /** The clock, right-aligned and bold. */
    private static final class TextRight {
        static void draw(Canvas c, String s, float x, float y, float size) {
            Ui.TXT.setTypeface(Ui.MONO_BOLD);
            Ui.TXT.setTextAlign(Paint.Align.RIGHT);
            Ui.TXT.setTextSize(size);
            Ui.TXT.setColor(Ui.AMBER);
            c.drawText(s, x, y, Ui.TXT);
            Ui.TXT.setTextAlign(Paint.Align.LEFT);
        }
    }
}
