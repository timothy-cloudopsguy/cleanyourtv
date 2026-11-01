package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.graphics.Canvas;
import android.view.KeyEvent;

import java.util.List;
import java.util.Locale;

/**
 * Three tabs: what is erupting, the cameras, and a wall of favourites. The wall shows refreshing pictures rather than
 * video: TVs like this one have only two video decoders. OK on any camera plays its live stream full screen.
 */
final class VolcanoPanel extends Panel {
    private static final String[] TABS = {"ACTIVITY", "CAMS", "WALL"};
    private static final int ACTIVITY = 0;
    private static final int CAMS = 1;
    private static final int WALL = 2;
    private static final String PREF_WALL = "volcano_wall";
    private static final String STARTING_WALL = "f9-oSpYpubg,uC6nVnMfRDY,R98Zzr-H2FA,F1cam,w6WsMTQw9ac,gsitkin_gsig";
    private static final float L = DeckView.X0;
    private static final float R = DeckView.X1;
    private static final float B = DeckView.Y1;
    private static final float TOP = 196f;
    private static final float LIST_R = 1170f;
    private static final float DETAIL_L = 1192f;
    private static final float ROW_H = 84f;
    private static final int VISIBLE = 10;

    private final CamBoard board;

    private int tab;
    private int activityRow;
    private String qrFor = "";
    private boolean[][] qr;

    VolcanoPanel(Deck deck, Context context) {
        super(deck);
        board = new CamBoard(deck, context, VolcanoData.CAMS, PREF_WALL, STARTING_WALL, Ui.RED);
    }

    @Override String railName() { return "VOLCANO"; }
    @Override String title() { return "VOLCANO WATCH"; }
    @Override int accent() { return Ui.RED; }

    @Override
    boolean back() {
        return board.back();
    }

    @Override
    boolean key(int key) {
        if (board.overlayKey(key)) return true;
        if (tab == ACTIVITY) return activityKey(key);
        if (tab == CAMS) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) return moveTab(-1);
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return moveTab(1);
            return board.camsKey(key);
        }
        return board.wallKey(key) || (key == KeyEvent.KEYCODE_DPAD_LEFT && moveTab(-1));
    }

    private boolean moveTab(int by) {
        int next = tab + by;
        if (next < 0) return false;
        if (next < TABS.length) {
            tab = next;
            deck.sfx.tick();
        }
        return true;
    }

    private boolean activityKey(int key) {
        List<VolcanoData.Report> reports = deck.store.volcanoReports();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) return moveTab(-1);
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return moveTab(1);
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = activityRow + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next < reports.size()) {
                activityRow = next;
                deck.sfx.tick();
            }
            return true;
        }
        if (Ui.isOk(key) && activityRow < reports.size()) {
            deck.sfx.confirm();
            deck.actions.openLink(reports.get(activityRow).link);
            return true;
        }
        return false;
    }

    @Override
    void draw(Canvas c, long now) {
        board.focused = focused;
        board.sync(tab == WALL);
        if (board.showingFullScreen()) {
            board.drawLarge(c, now);
            return;
        }
        drawTabs(c);
        if (tab == ACTIVITY) drawActivity(c, now);
        else if (tab == CAMS) board.drawCams(c, now);
        else board.drawWall(c, now);
        board.drawOverlays(c, now);
    }

    @Override
    void onLeave() {
        board.stop();
    }

    private void drawTabs(Canvas c) {
        float x = L;
        for (int i = 0; i < TABS.length; i++) {
            boolean on = i == tab;
            Ui.card(c, x, 118, x + 200, 170, on ? Ui.RED : Ui.PANEL);
            String name = i == WALL ? "WALL " + board.wallSize() : TABS[i];
            Ui.centre(c, name, x + 100, 154, 25, on ? Ui.BG : Ui.DIM);
            x += 212;
        }
    }

    private void drawActivity(Canvas c, long now) {
        List<VolcanoData.Report> reports = deck.store.volcanoReports();
        if (reports.isEmpty()) {
            Ui.mono(c, "Pulling volcano reports...", L + 20, TOP + 60, 28, Ui.FAINT);
            return;
        }
        activityRow = Math.min(activityRow, reports.size() - 1);
        int first = Math.max(0, Math.min(activityRow - VISIBLE / 2, reports.size() - VISIBLE));
        for (int i = 0; i < VISIBLE && first + i < reports.size(); i++) {
            VolcanoData.Report r = reports.get(first + i);
            float top = TOP + i * ROW_H;
            float bottom = top + ROW_H - 8;
            boolean on = first + i == activityRow;
            Ui.card(c, L, top, LIST_R, bottom, on ? Ui.PANEL_HI : Ui.PANEL);
            Ui.FILL.setColor(levelColor(r.level));
            drawTriangle(c, L + 32, top + 39, 15);
            Ui.line(c, r.name, L + 62, top + 34, 700, 28, Ui.TEXT, Ui.SANS);
            Ui.line(c, r.place, L + 62, top + 63, 700, 19, Ui.DIM, Ui.MONO);
            Ui.right(c, levelWord(r.level), LIST_R - 18, top + 48, 22, levelColor(r.level));
            if (on && focused) Ui.focus(c, L, top, LIST_R, bottom, levelColor(r.level), now);
        }
        drawReport(c, reports.get(activityRow));
    }

    private void drawReport(Canvas c, VolcanoData.Report r) {
        Ui.card(c, DETAIL_L, TOP, R, B, Ui.PANEL);
        float width = R - DETAIL_L - 48;
        Ui.bold(c, r.status.toUpperCase(Locale.ROOT), DETAIL_L + 24, TOP + 46, 22, levelColor(r.level));
        float y = Ui.para(c, r.name, DETAIL_L + 24, TOP + 100, width, 44, Ui.TEXT, Ui.SANS, 2);
        Ui.para(c, r.summary, DETAIL_L + 24, y + 6, width, 25, Ui.DIM, Ui.SANS, 11);
        if (!r.link.equals(qrFor)) {
            qrFor = r.link;
            qr = r.link.isEmpty() ? null : Qr.encode(r.link);
        }
        if (qr != null) {
            Ui.qr(c, qr, DETAIL_L + 24, B - 184, 164);
            Ui.mono(c, "SCAN OR PRESS OK", DETAIL_L + 204, B - 112, 21, Ui.TEXT);
            Ui.mono(c, "for the full report", DETAIL_L + 204, B - 80, 20, Ui.DIM);
        }
    }

    private static int levelColor(String level) {
        switch (level) {
            case "RED": return Ui.RED;
            case "ORANGE": return 0xFFFF8A3C;
            case "YELLOW": return Ui.AMBER;
            case "NEW": return Ui.MAGENTA;
            default: return Ui.DIM;
        }
    }

    private static String levelWord(String level) {
        switch (level) {
            case "NEW": return "NEW ERUPTION";
            case "ONGOING": return "ONGOING";
            default: return level + " ALERT";
        }
    }

    static void drawTriangle(Canvas c, float x, float y, float size) {
        android.graphics.Path path = TRIANGLE;
        path.rewind();
        path.moveTo(x, y - size);
        path.lineTo(x + size, y + size * 0.8f);
        path.lineTo(x - size, y + size * 0.8f);
        path.close();
        c.drawPath(path, Ui.FILL);
    }

    private static final android.graphics.Path TRIANGLE = new android.graphics.Path();

}
