package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Canvas;
import android.view.KeyEvent;

import java.util.List;

/** The curated news: four desks, a list, and the selected story with a QR code to read it on a phone. */
final class IntelPanel extends Panel {
    private static final int[] DESK_COLOR = {Ui.RED, Ui.BLUE, Ui.GREEN, Ui.MAGENTA};
    private static final float LIST_R = 1170f;
    private static final float DETAIL_L = 1192f;
    private static final float ROW_H = 84f;
    private static final float LIST_TOP = 196f;
    private static final int VISIBLE = 10;

    private int desk;
    private final int[] selected = new int[Store.CATEGORIES.length];
    private String qrFor = "";
    private boolean[][] qr;

    IntelPanel(Deck deck) {
        super(deck);
    }

    @Override String railName() { return "INTEL"; }
    @Override String title() { return "INTEL FEED"; }
    @Override int accent() { return DESK_COLOR[desk]; }

    @Override
    boolean key(int key) {
        List<Headline> items = deck.store.headlines(desk);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (desk == 0) return false;
                desk--;
                deck.sfx.tick();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (desk < Store.CATEGORIES.length - 1) {
                    desk++;
                    deck.sfx.tick();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (selected[desk] > 0) {
                    selected[desk]--;
                    deck.sfx.tick();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (selected[desk] < items.size() - 1) {
                    selected[desk]++;
                    deck.sfx.tick();
                }
                return true;
            default:
                if (!Ui.isOk(key)) return false;
                if (selected[desk] < items.size()) {
                    deck.sfx.confirm();
                    deck.actions.openLink(items.get(selected[desk]).link);
                }
                return true;
        }
    }

    @Override
    void draw(Canvas c, long now) {
        float x = DeckView.X0;
        for (int i = 0; i < Store.CATEGORIES.length; i++) {
            boolean on = i == desk;
            Ui.card(c, x, 118, x + 200, 170, on ? DESK_COLOR[i] : Ui.PANEL);
            Ui.centre(c, Store.CATEGORIES[i], x + 100, 154, 25, on ? Ui.BG : Ui.DIM);
            x += 212;
        }
        List<Headline> items = deck.store.headlines(desk);
        if (items.isEmpty()) {
            Ui.mono(c, "Pulling feeds...", DeckView.X0 + 20, LIST_TOP + 60, 28, Ui.FAINT);
            return;
        }
        int sel = Math.min(selected[desk], items.size() - 1);
        selected[desk] = sel;
        int first = Math.max(0, Math.min(sel - VISIBLE / 2, items.size() - VISIBLE));
        for (int i = 0; i < VISIBLE && first + i < items.size(); i++) {
            drawRow(c, items.get(first + i), LIST_TOP + i * ROW_H, first + i == sel, now);
        }
        drawDetail(c, items.get(sel), now);
    }

    private void drawRow(Canvas c, Headline h, float top, boolean on, long now) {
        float bottom = top + ROW_H - 8;
        Ui.card(c, DeckView.X0, top, LIST_R, bottom, on ? Ui.PANEL_HI : Ui.PANEL);
        Ui.FILL.setColor(DESK_COLOR[desk]);
        c.drawRect(DeckView.X0, top + 8, DeckView.X0 + 5, bottom - 8, Ui.FILL);
        Ui.line(c, h.title, DeckView.X0 + 22, top + 33, LIST_R - DeckView.X0 - 40, 25, Ui.TEXT, Ui.SANS);
        Ui.mono(c, h.source, DeckView.X0 + 22, top + 63, 19, Ui.DIM);
        Ui.right(c, Parsers.ago(h.time, now), LIST_R - 18, top + 63, 19, Ui.FAINT);
        if (on && focused) Ui.focus(c, DeckView.X0, top, LIST_R, bottom, DESK_COLOR[desk], now);
    }

    private void drawDetail(Canvas c, Headline h, long now) {
        float l = DETAIL_L;
        float r = DeckView.X1;
        float width = r - l - 48;
        Ui.card(c, l, LIST_TOP, r, DeckView.Y1, Ui.PANEL);
        Ui.bold(c, h.source.toUpperCase(java.util.Locale.ROOT), l + 24, LIST_TOP + 46, 23, DESK_COLOR[desk]);
        float y = Ui.para(c, h.title, l + 24, LIST_TOP + 96, width, 34, Ui.TEXT, Ui.SANS, 4);
        Ui.para(c, h.summary, l + 24, y + 14, width, 25, Ui.DIM, Ui.SANS, 8);
        if (!h.link.equals(qrFor)) {
            qrFor = h.link;
            qr = h.link.isEmpty() ? null : Qr.encode(h.link);
        }
        float bottom = DeckView.Y1 - 24;
        if (qr != null) {
            Ui.qr(c, qr, l + 24, bottom - 200, 200);
            Ui.mono(c, "SCAN TO READ", l + 244, bottom - 150, 22, Ui.TEXT);
            Ui.mono(c, "or OK to open here", l + 244, bottom - 116, 20, Ui.DIM);
        }
    }
}
