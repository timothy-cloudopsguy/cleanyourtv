package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Canvas;
import android.graphics.Path;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The status board: platforms with a light and a latency trace measured from this TV; select one for its incidents. Its
 * last row opens the list of checks to add or remove, including any status page the owner types in.
 */
final class StatusPanel extends Panel {
    private static final float LIST_R = 1000f;
    private static final float ROW_H = 104f;
    private static final int ROWS = 8;
    private static final float MANAGE_H = 62f;
    private static final float PICK_ROW_H = 62f;
    private static final int PICK_ROWS = 14;
    private final Path trace = new Path();
    private int selected;
    private boolean managing;
    private int pickRow;
    private String note = "";
    private long noteUntil;

    StatusPanel(Deck deck) {
        super(deck);
    }

    @Override String railName() { return "STATUS"; }
    @Override String title() { return managing ? "STATUS CHECKS" : "PLATFORM STATUS"; }
    @Override int accent() { return Ui.GREEN; }

    @Override
    boolean back() {
        if (managing) {
            managing = false;
            return true;
        }
        return false;
    }

    @Override
    boolean key(int key) {
        return managing ? pickKey(key) : boardKey(key);
    }

    private boolean boardKey(int key) {
        int last = deck.store.services.size();
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = selected + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next <= last) {
                selected = next;
                deck.sfx.tick();
            }
            return true;
        }
        if (Ui.isOk(key) && selected == last) {
            managing = true;
            pickRow = 0;
            deck.sfx.confirm();
            return true;
        }
        return false;
    }

    /** The catalogue, then anything of the owner's own, under a first row for adding one. */
    private List<StatusData.Source> pickList() {
        List<StatusData.Source> out = new ArrayList<>(java.util.Arrays.asList(StatusData.CATALOG));
        for (Store.Service s : deck.store.services) if (s.source.custom()) out.add(s.source);
        return out;
    }

    private boolean pickKey(int key) {
        List<StatusData.Source> list = pickList();
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = pickRow + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next <= list.size()) {
                pickRow = next;
                deck.sfx.tick();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) return true;
        if (!Ui.isOk(key)) return false;
        if (pickRow == 0) {
            askForAddress();
            return true;
        }
        StatusData.Source source = list.get(pickRow - 1);
        if (deck.store.hasCheck(source.name)) {
            deck.store.removeCheck(source.name);
            show("Removed " + source.name);
            pickRow = Math.min(pickRow, pickList().size());
            deck.sfx.tick();
        } else if (deck.store.addCheckAndSave(source)) {
            show("Added " + source.name);
            deck.sfx.confirm();
        } else {
            show("Your board is full: remove one first");
            deck.sfx.tick();
        }
        return true;
    }

    private void askForAddress() {
        deck.sfx.tick();
        deck.actions.askText("Add a status page",
                "Enter a status page, like https://www.githubstatus.com, or an RSS or Atom feed of its incidents. An ordinary web page will not work.",
                "https://", deck.store::tryToAdd);
    }

    private void show(String text) {
        note = text;
        noteUntil = System.currentTimeMillis() + 3000;
    }

    @Override
    void draw(Canvas c, long now) {
        if (managing) {
            drawPicker(c, now);
            return;
        }
        List<Store.Service> services = new ArrayList<>(deck.store.services);
        selected = Math.min(selected, services.size());
        int first = Math.max(0, Math.min(selected - ROWS / 2, services.size() - ROWS));
        for (int i = 0; i < ROWS && first + i < services.size(); i++) {
            drawService(c, services.get(first + i), DeckView.Y0 + i * ROW_H, first + i == selected, now);
        }
        float top = DeckView.Y1 - MANAGE_H - 22;
        boolean on = selected == services.size();
        Ui.card(c, DeckView.X0, top, LIST_R, top + MANAGE_H, on ? Ui.PANEL_HI : Ui.PANEL);
        Ui.bold(c, "+ ADD OR REMOVE CHECKS", DeckView.X0 + 26, top + 42, 26, on ? Ui.GREEN : Ui.DIM);
        Ui.right(c, services.size() > ROWS ? (first + 1) + "-" + Math.min(first + ROWS, services.size()) + " of " + services.size() : services.size() + " checks",
                LIST_R - 24, top + 40, 22, Ui.DIM);
        if (on && focused) Ui.focus(c, DeckView.X0, top, LIST_R, top + MANAGE_H, Ui.GREEN, now);
        if (on) drawHelp(c, "OK opens the list");
        else drawDetail(c, services.get(selected));
    }

    private void drawPicker(Canvas c, long now) {
        List<StatusData.Source> list = pickList();
        int count = list.size() + 1;
        pickRow = Math.min(pickRow, count - 1);
        int first = Math.max(0, Math.min(pickRow - PICK_ROWS / 2, count - PICK_ROWS));
        for (int i = 0; i < PICK_ROWS && first + i < count; i++) {
            int row = first + i;
            float top = DeckView.Y0 + i * PICK_ROW_H;
            float bottom = top + PICK_ROW_H - 8;
            boolean on = row == pickRow;
            Ui.card(c, DeckView.X0, top, LIST_R, bottom, on ? Ui.PANEL_HI : Ui.PANEL);
            if (row == 0) {
                Ui.bold(c, "+ ADD YOUR OWN STATUS PAGE", DeckView.X0 + 26, top + 37, 25, Ui.GREEN);
            } else {
                StatusData.Source source = list.get(row - 1);
                boolean checked = deck.store.hasCheck(source.name);
                Ui.line(c, source.name, DeckView.X0 + 26, top + 38, 500, 27, checked ? Ui.TEXT : Ui.DIM, Ui.SANS);
                if (source.custom()) Ui.mono(c, "YOUR OWN", LIST_R - 280, top + 36, 19, Ui.FAINT);
                Ui.right(c, checked ? "ON BOARD" : "OFF", LIST_R - 24, top + 36, 22, checked ? Ui.GREEN : Ui.FAINT);
            }
            if (on && focused) Ui.focus(c, DeckView.X0, top, LIST_R, bottom, Ui.GREEN, now);
        }
        drawHelp(c, pickRow == 0 ? "OK types an address" : "OK adds or removes it. Back returns to the board");
        Store store = deck.store;
        float l = LIST_R + 22;
        if (now < noteUntil) {
            Ui.para(c, note, l + 24, DeckView.Y1 - 150, DeckView.X1 - l - 48, 26, Ui.AMBER, Ui.SANS, 3);
        } else if (!store.addMessage.isEmpty()) {
            Ui.para(c, store.addMessage, l + 24, DeckView.Y1 - 150, DeckView.X1 - l - 48, 26, store.addFailed ? Ui.RED : store.adding ? Ui.AMBER : Ui.GREEN, Ui.SANS, 4);
        }
    }

    /** What can be added, since an ordinary page will not do. */
    private void drawHelp(Canvas c, String action) {
        float l = LIST_R + 22;
        float r = DeckView.X1;
        float width = r - l - 48;
        Ui.card(c, l, DeckView.Y0, r, DeckView.Y1 - 22, Ui.PANEL);
        Ui.heading(c, "WHAT CAN GO ON THE BOARD", l + 24, DeckView.Y0 + 46);
        Ui.mono(c, deck.store.services.size() + " of " + StatusData.MAX_CHECKS + " in use", l + 24, DeckView.Y0 + 90, 23, Ui.DIM);
        float y = Ui.para(c, "Pick from the list, or add your own. Your own has to be one of these:", l + 24, DeckView.Y0 + 140, width, 26, Ui.TEXT, Ui.SANS, 3) + 16;
        y = Ui.para(c, "1. A status page run on Statuspage, like https://www.githubstatus.com. Most companies use it. Enter its home address.", l + 24, y, width, 25, Ui.CYAN, Ui.SANS, 4) + 16;
        y = Ui.para(c, "2. An RSS or Atom feed of its incidents. This shows what was posted lately, not whether anything is down.", l + 24, y, width, 25, Ui.CYAN, Ui.SANS, 4) + 16;
        y = Ui.para(c, "An ordinary web page will not work. The address is checked when you add it.", l + 24, y, width, 25, Ui.DIM, Ui.SANS, 3) + 24;
        Ui.bold(c, action.toUpperCase(java.util.Locale.ROOT), l + 24, y, 22, Ui.GREEN);
    }

    private void drawService(Canvas c, Store.Service s, float top, boolean on, long now) {
        float bottom = top + ROW_H - 10;
        Ui.card(c, DeckView.X0, top, LIST_R, bottom, on ? Ui.PANEL_HI : Ui.PANEL);
        int color = Ui.statusColor(s.indicator);
        Ui.FILL.setColor(color);
        boolean calm = s.indicator.equals("none") || s.indicator.equals("unknown");
        float pulse = calm ? 0 : (float) (Math.sin(now / 180.0) * 0.5 + 0.5);
        c.drawCircle(DeckView.X0 + 30, top + 30, 11 + pulse * 4, Ui.FILL);
        Ui.line(c, s.name.toUpperCase(Locale.ROOT), DeckView.X0 + 54, top + 41, 330, 28, Ui.TEXT, Ui.MONO);
        Ui.line(c, s.description, DeckView.X0 + 26, top + 78, 360, 21, color, Ui.SANS);
        int ms = s.lastLatency();
        Ui.right(c, ms < 0 ? "-- ms" : ms + " ms", LIST_R - 24, top + 38, 24, Ui.AMBER);
        drawTrace(c, s, DeckView.X0 + 400, top + 46, LIST_R - 24, bottom - 10);
        if (on && focused) Ui.focus(c, DeckView.X0, top, LIST_R, bottom, color, now);
    }

    private void drawTrace(Canvas c, Store.Service s, float l, float t, float r, float b) {
        int n;
        int[] copy = new int[Store.Service.HISTORY_SIZE];
        int samples;
        synchronized (s) {
            samples = s.samples;
            n = Math.min(samples, copy.length);
            for (int i = 0; i < n; i++) copy[i] = s.latency[(samples - n + i) % copy.length];
        }
        Ui.FILL.setColor(Ui.LINE);
        c.drawRect(l, b, r, b + 1, Ui.FILL);
        if (n < 2) {
            Ui.mono(c, "collecting...", l, b - 12, 18, Ui.FAINT);
            return;
        }
        int max = 1;
        for (int i = 0; i < n; i++) max = Math.max(max, copy[i]);
        trace.rewind();
        for (int i = 0; i < n; i++) {
            float x = l + (r - l) * i / (Store.Service.HISTORY_SIZE - 1);
            float y = b - (b - t) * copy[i] / max;
            if (i == 0) trace.moveTo(x, y);
            else trace.lineTo(x, y);
        }
        Ui.STROKE.setColor(Ui.CYAN);
        Ui.STROKE.setStrokeWidth(3);
        c.drawPath(trace, Ui.STROKE);
    }

    private void drawDetail(Canvas c, Store.Service s) {
        float l = LIST_R + 22;
        float r = DeckView.X1;
        float width = r - l - 48;
        Ui.card(c, l, DeckView.Y0, r, DeckView.Y1 - 22, Ui.PANEL);
        Ui.heading(c, s.name.toUpperCase(Locale.ROOT) + " INCIDENTS", l + 24, DeckView.Y0 + 46);
        Parsers.Summary summary = s.summary;
        if (summary == null) {
            Ui.mono(c, "Waiting for the summary...", l + 24, DeckView.Y0 + 100, 24, Ui.FAINT);
            return;
        }
        float y = DeckView.Y0 + 96;
        if (summary.incidents.isEmpty()) {
            Ui.bold(c, "NO OPEN INCIDENTS", l + 24, y, 32, Ui.GREEN);
            y += 50;
        }
        for (int i = 0; i < Math.min(3, summary.incidents.size()); i++) {
            Parsers.Incident incident = summary.incidents.get(i);
            y = Ui.para(c, incident.name, l + 24, y, width, 28, Ui.AMBER, Ui.SANS, 2);
            Ui.mono(c, (incident.impact + " / " + incident.status).toUpperCase(Locale.ROOT), l + 24, y, 19, Ui.DIM);
            y = Ui.para(c, incident.update, l + 24, y + 34, width, 22, Ui.TEXT, Ui.SANS, 4) + 12;
        }
        int healthy = summary.components - summary.degraded.size();
        if (summary.components > 0) {
            Ui.mono(c, healthy + " of " + summary.components + " components operational", l + 24, y + 18, 24, Ui.DIM);
            Ui.bar(c, l + 24, y + 36, r - 24, y + 50, healthy / (float) summary.components,
                    summary.degraded.isEmpty() ? Ui.GREEN : Ui.AMBER);
            y += 90;
        }
        for (int i = 0; i < Math.min(8, summary.degraded.size()) && y < DeckView.Y1 - 50; i++) {
            Ui.line(c, "! " + summary.degraded.get(i), l + 24, y, width, 23, Ui.RED, Ui.MONO);
            y += 32;
        }
    }
}
