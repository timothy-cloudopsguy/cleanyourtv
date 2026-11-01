package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A list of cameras and a wall of favourites, shared by the screens that have them. The screen owns its tabs and passes
 * keys here: a key returned unused is its to act on.
 */
final class CamBoard {
    private static final int WALL_MAX = 6;
    private static final int WALL_COLS = 3;
    private static final float L = DeckView.X0;
    private static final float R = DeckView.X1;
    private static final float B = DeckView.Y1;
    private static final float TOP = 196f;
    private static final float LIST_R = 1170f;
    private static final float DETAIL_L = 1192f;
    private static final float ROW_H = 84f;
    private static final int VISIBLE = 10;
    private static final float GAP = 14f;
    private static final long NOTE_MS = 3000;
    /** This TV lists two concurrent video decoders. */
    private static final int LIVE_TILES = 2;

    private final Deck deck;
    private final VolcanoData.Cam[] cams;
    private final String wallPref;
    private final int accent;
    private final CamImages images;
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF dest = new RectF();
    private final List<String> wall;

    /** Set by the screen before drawing. */
    boolean focused;
    private int camRow;
    private int wallTile;
    private boolean menu;
    private VolcanoData.Cam menuCam;
    private VolcanoData.Cam fullScreen;
    private String note = "";
    private long noteUntil;

    CamBoard(Deck deck, Context context, VolcanoData.Cam[] cams, String wallPref, String startingWall, int accent) {
        this.deck = deck;
        this.cams = cams;
        this.wallPref = wallPref;
        this.accent = accent;
        images = new CamImages(context);
        wall = new ArrayList<>(VolcanoData.readWall(cams, deck.prefs.getString(wallPref, startingWall), WALL_MAX));
    }

    int wallSize() {
        return wall.size();
    }

    private VolcanoData.Cam cam(String id) {
        return VolcanoData.cam(cams, id);
    }

    /** True when an overlay took Back. */
    boolean back() {
        if (fullScreen != null) {
            fullScreen = null;
            return true;
        }
        if (menu) {
            menu = false;
            return true;
        }
        return false;
    }

    /** True when an overlay is up and takes every key. */
    boolean overlayKey(int key) {
        if (fullScreen != null) {
            if (Ui.isOk(key)) fullScreen = null;
            return true;
        }
        return menu && menuKey(key);
    }

    boolean showingFullScreen() {
        return fullScreen != null;
    }

    /** Stops any video, for when the screen or the tab is left. */
    void stop() {
        deck.liveKey = "";
        deck.actions.showLive(Collections.emptyList(), Collections.emptyList());
    }

    /** What sits over the list or wall. */
    void drawOverlays(Canvas c, long now) {
        if (menu) drawMenu(c);
        if (now < noteUntil) {
            Ui.card(c, R - 620, 118, R, 170, Ui.PANEL_HI);
            Ui.bold(c, note, R - 600, 154, 25, Ui.AMBER);
        }
    }

    void drawLarge(Canvas c, long now) {
        drawFullScreen(c, now);
    }

    private boolean menuKey(int key) {
        if (Ui.isOk(key)) {
            menu = false;
            watch(menuCam);
        } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            menu = false;
            toggleWall(menuCam);
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            menu = false;
            deck.sfx.tick();
        }
        return true;
    }
    private void watch(VolcanoData.Cam cam) {
        deck.sfx.confirm();
        if (!cam.video()) {
            fullScreen = cam;
            return;
        }
        // A stream's address changes when its channel restarts it: then the channel's list is the place to look.
        deck.actions.watch(images.failing(cam.id) ? cam.channelUrl : cam.videoUrl);
    }
    private void toggleWall(VolcanoData.Cam cam) {
        if (wall.remove(cam.id)) {
            show("Removed from the wall");
            wallTile = Math.max(0, Math.min(wallTile, wall.size() - 1));
        } else if (wall.size() >= WALL_MAX) {
            show("The wall is full: remove one first");
            deck.sfx.tick();
            return;
        } else {
            wall.add(cam.id);
            show("Added to the wall");
        }
        deck.prefs.edit().putString(wallPref, String.join(",", wall)).apply();
        deck.sfx.confirm();
    }
    private void show(String text) {
        note = text;
        noteUntil = System.currentTimeMillis() + NOTE_MS;
    }
    boolean camsKey(int key) {
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = camRow + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next < cams.length) {
                camRow = next;
                deck.sfx.app(camRow);
            }
            return true;
        }
        if (Ui.isOk(key)) {
            menuCam = cams[camRow];
            menu = true;
            deck.sfx.tick();
            return true;
        }
        return false;
    }
    boolean wallKey(int key) {
        int count = wall.size();
        int col = wallTile % WALL_COLS;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col == 0 || count == 0) return false;
                wallTile--;
                deck.sfx.tick();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < WALL_COLS - 1 && wallTile + 1 < count) {
                    wallTile++;
                    deck.sfx.tick();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (wallTile >= WALL_COLS) {
                    wallTile -= WALL_COLS;
                    deck.sfx.tick();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (wallTile + WALL_COLS < count) {
                    wallTile += WALL_COLS;
                    deck.sfx.tick();
                }
                return true;
            default:
                if (!Ui.isOk(key)) return false;
                if (count > 0) {
                    menuCam = cam(wall.get(wallTile));
                    menu = true;
                    deck.sfx.tick();
                }
                return true;
        }
    }
    /** Which wall tiles get real video: the first streams with no current picture of their own. */
    private List<String> liveIds() {
        List<String> ids = new ArrayList<>();
        for (String id : wall) {
            VolcanoData.Cam cam = cam(id);
            if (cam.poster && ids.size() < LIVE_TILES) ids.add(id);
        }
        return ids;
    }
    /** Call each frame: real video only while the wall itself is showing. */
    void sync(boolean showing) {
        List<String> ids = showing ? liveIds() : Collections.emptyList();
        String key = String.join(",", ids) + "|" + String.join(",", wall);
        if (ids.isEmpty()) key = "";
        if (!key.equals(deck.liveKey)) {
            deck.liveKey = key;
            List<float[]> boxes = new ArrayList<>();
            for (String id : ids) boxes.add(tileImageBox(wall.indexOf(id)));
            deck.actions.showLive(ids, boxes);
        }
        deck.actions.setLiveVisible(!menu && fullScreen == null);
    }
    private static float[] tileImageBox(int index) {
        float tileW = (R - L - GAP * (WALL_COLS - 1)) / WALL_COLS;
        float tileH = (B - TOP - GAP) / 2;
        float l = L + (index % WALL_COLS) * (tileW + GAP);
        float t = TOP + (index / WALL_COLS) * (tileH + GAP);
        return new float[]{l + 6, t + 6, l + tileW - 6, t + tileH - 76};
    }
    void drawCams(Canvas c, long now) {
                int first = Math.max(0, Math.min(camRow - VISIBLE / 2, cams.length - VISIBLE));
        for (int i = 0; i < VISIBLE && first + i < cams.length; i++) {
            VolcanoData.Cam cam = cams[first + i];
            float top = TOP + i * ROW_H;
            float bottom = top + ROW_H - 8;
            boolean on = first + i == camRow;
            Ui.card(c, L, top, LIST_R, bottom, on ? Ui.PANEL_HI : Ui.PANEL);
            Ui.line(c, cam.volcano, L + 22, top + 34, 620, 28, Ui.TEXT, Ui.SANS);
            Ui.line(c, cam.view, L + 22, top + 63, 620, 19, Ui.DIM, Ui.MONO);
            if (wall.contains(cam.id)) Ui.bold(c, "ON WALL", LIST_R - 346, top + 48, 20, Ui.CYAN);
            badge(c, cam, LIST_R - 18, top + 48);
            if (on && focused) Ui.focus(c, L, top, LIST_R, bottom, accent, now);
        }
        VolcanoData.Cam cam = cams[camRow];
        Ui.card(c, DETAIL_L, TOP, R, B, Ui.PANEL);
        float w = R - DETAIL_L - 32;
        drawPicture(c, cam, DETAIL_L + 16, TOP + 16, DETAIL_L + 16 + w, TOP + 16 + w * 9 / 16, now);
        float y = TOP + 16 + w * 9 / 16 + 56;
        Ui.line(c, cam.volcano, DETAIL_L + 24, y, w - 16, 40, Ui.TEXT, Ui.SANS);
        Ui.line(c, cam.view, DETAIL_L + 24, y + 42, w - 16, 25, Ui.DIM, Ui.SANS);
        Ui.line(c, "From " + cam.source + (cam.poster ? ". Channel poster, not current" : ""), DETAIL_L + 24, y + 84, w - 16, 21, Ui.FAINT, Ui.MONO);
        Ui.mono(c, cam.video() ? "OK: watch live, or add to the wall" : "OK: view large, or add to the wall", DETAIL_L + 24, y + 128, 22, Ui.TEXT);
    }
    private static void badge(Canvas c, VolcanoData.Cam cam, float right, float y) {
        Ui.right(c, cam.video() ? "LIVE VIDEO" : "SNAPSHOT", right, y, 21, cam.video() ? Ui.RED : Ui.BLUE);
    }
    void drawWall(Canvas c, long now) {
        if (wall.isEmpty()) {
            Ui.bold(c, "YOUR WALL IS EMPTY", L + 20, TOP + 90, 40, Ui.TEXT);
            Ui.mono(c, "Open CAMS, press OK on a camera, then Right to add it.", L + 20, TOP + 144, 26, Ui.DIM);
            return;
        }
        wallTile = Math.max(0, Math.min(wallTile, wall.size() - 1));
        float tileW = (R - L - GAP * (WALL_COLS - 1)) / WALL_COLS;
        float tileH = (B - TOP - GAP) / 2;
        List<String> live = liveIds();
        for (int i = 0; i < wall.size(); i++) {
            VolcanoData.Cam cam = cam(wall.get(i));
            float l = L + (i % WALL_COLS) * (tileW + GAP);
            float t = TOP + (i / WALL_COLS) * (tileH + GAP);
            float r = l + tileW;
            float b = t + tileH;
            Ui.card(c, l, t, r, b, Ui.PANEL);
            drawPicture(c, cam, l + 6, t + 6, r - 6, b - 76, now);
            Ui.line(c, cam.volcano, l + 16, b - 42, tileW - 170, 27, Ui.TEXT, Ui.SANS);
            Ui.line(c, cam.view, l + 16, b - 14, tileW - 32, 19, Ui.DIM, Ui.MONO);
            boolean playing = live.contains(cam.id);
            long at = images.fetchedAt(cam.id);
            String state = playing ? "PLAYING" : cam.poster ? "POSTER" : at == 0 ? "" : Parsers.ago(at, now) + " ago";
            Ui.right(c, state, r - 16, b - 42, 21, playing ? Ui.RED : cam.poster ? Ui.FAINT : Ui.AMBER);
            if (focused && i == wallTile) Ui.focus(c, l, t, r, b, accent, now);
        }
    }
    /** The picture filling the box, cropped rather than squeezed. */
    private void drawPicture(Canvas c, VolcanoData.Cam cam, float l, float t, float r, float b, long now) {
        Ui.FILL.setColor(0xFF05070C);
        c.drawRect(l, t, r, b, Ui.FILL);
        Bitmap image = images.get(cam, now);
        if (image == null) {
            String word = images.failing(cam.id) ? (cam.video() ? "Stream has moved: OK opens the channel" : "Camera not answering") : "Loading...";
            Ui.line(c, word, l + 20, (t + b) / 2 + 9, r - l - 40, 24, Ui.FAINT, Ui.MONO);
            return;
        }
        float boxRatio = (r - l) / (b - t);
        int w = image.getWidth();
        int h = image.getHeight();
        if (w / (float) h > boxRatio) {
            int cut = (int) ((w - h * boxRatio) / 2);
            source.set(cut, 0, w - cut, h);
        } else {
            int cut = (int) ((h - w / boxRatio) / 2);
            source.set(0, cut, w, h - cut);
        }
        dest.set(l, t, r, b);
        c.drawBitmap(image, source, dest, picture);
    }
    private void drawMenu(Canvas c) {
        float w = 760;
        float h = 300;
        float mx = (L + R) / 2;
        float l = mx - w / 2;
        float t = (TOP + B) / 2 - h / 2;
        boolean on = wall.contains(menuCam.id);
        Ui.card(c, l, t, l + w, t + h, 0xF8060A12);
        Ui.STROKE.setColor(accent);
        Ui.STROKE.setStrokeWidth(3);
        dest.set(l, t, l + w, t + h);
        c.drawRoundRect(dest, 10, 10, Ui.STROKE);
        Ui.centre(c, menuCam.volcano.toUpperCase(Locale.ROOT), mx, t + 62, 34, Ui.TEXT);
        Ui.centre(c, "OK", l + 200, t + 150, 34, accent);
        Ui.centre(c, menuCam.video() ? "WATCH LIVE" : "VIEW LARGE", l + 200, t + 192, 23, Ui.DIM);
        Ui.centre(c, "RIGHT", l + w - 200, t + 150, 34, Ui.CYAN);
        Ui.centre(c, on ? "REMOVE FROM WALL" : "ADD TO WALL", l + w - 200, t + 192, 23, Ui.DIM);
        Ui.centre(c, "LEFT OR BACK CLOSES", mx, t + 268, 20, Ui.FAINT);
    }
    private void drawFullScreen(Canvas c, long now) {
        Ui.FILL.setColor(Ui.BG);
        c.drawRect(-4, -4, DeckView.W + 4, DeckView.H + 4, Ui.FILL);
        drawPicture(c, fullScreen, 0, 0, DeckView.W, DeckView.H, now);
        Ui.card(c, 40, DeckView.H - 112, 1200, DeckView.H - 32, 0xE0060A12);
        Ui.bold(c, fullScreen.volcano, 64, DeckView.H - 62, 34, Ui.TEXT);
        long at = images.fetchedAt(fullScreen.id);
        Ui.right(c, fullScreen.view + (at == 0 ? "" : ", " + Parsers.ago(at, now) + " ago"), 1180, DeckView.H - 62, 23, Ui.DIM);
    }
}
