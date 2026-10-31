package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Canvas;
import android.view.KeyEvent;

import java.util.Locale;
import java.util.Random;

/** Incident Commander on the D-pad: move across the rack, OK to fix. IncidentGame holds the rules. */
final class GamePanel extends Panel {
    private static final String PREF_BEST = "best_score";
    private static final float RACK_L = DeckView.X0;
    private static final float RACK_T = DeckView.Y0 + 104;
    private static final float CELL_W = 1240f / IncidentGame.COLS;
    private static final float CELL_H = 816f / IncidentGame.ROWS;
    private static final String[] HOSTS = {"web", "api", "db", "dns", "edge", "auth"};

    private IncidentGame game;
    private long lastFrame;

    GamePanel(Deck deck) {
        super(deck);
    }

    static int best(Deck deck) {
        return deck.prefs.getInt(PREF_BEST, 0);
    }

    @Override String railName() { return "GAME"; }
    @Override String title() { return "INCIDENT COMMANDER"; }
    @Override int accent() { return Ui.RED; }

    @Override
    void onShown() {
        lastFrame = 0;
    }

    @Override
    boolean key(int key) {
        if (game == null || game.over) {
            if (Ui.isOk(key)) {
                game = new IncidentGame(new Random());
                lastFrame = 0;
                deck.sfx.confirm();
                return true;
            }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        if (Ui.isOk(key)) {
            playEvent(game.press());
            saveBest();
            return true;
        }
        int dx = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return false;
        if (game.move(dx, dy)) {
            deck.sfx.tick();
            return true;
        }
        // Left off the rack goes to the rail, which pauses the game; the other edges just stop.
        return dx != -1;
    }

    private void playEvent(int event) {
        switch (event) {
            case IncidentGame.EVENT_FIXED:
                deck.sfx.fixed(game.combo);
                break;
            case IncidentGame.EVENT_MISS:
                deck.sfx.tick();
                break;
            case IncidentGame.EVENT_WENT_DOWN:
                deck.sfx.alarm();
                break;
            case IncidentGame.EVENT_OVER:
                deck.sfx.gameOver();
                saveBest();
                break;
            default:
                break;
        }
    }

    private void saveBest() {
        if (game.score > best(deck)) deck.prefs.edit().putInt(PREF_BEST, game.score).apply();
    }

    @Override
    void draw(Canvas c, long now) {
        if (game != null && !game.over && focused) {
            if (lastFrame > 0) playEvent(game.update(Math.min(0.1f, (now - lastFrame) / 1000f)));
            lastFrame = now;
        } else {
            lastFrame = 0;
        }
        drawHud(c);
        drawRack(c, now);
        drawHelp(c);
        if (game == null) {
            banner(c, "INCIDENT COMMANDER", "Move to the rack and press OK to start your shift", Ui.CYAN);
        } else if (game.over) {
            String detail = "Score " + Ui.number(game.score) + ", " + game.resolved + " incidents resolved. OK for another shift";
            banner(c, "ERROR BUDGET EXHAUSTED", detail, Ui.RED);
        } else if (!focused) {
            banner(c, "PAUSED", "Press Right to return to the rack", Ui.AMBER);
        }
    }

    private void drawHud(Canvas c) {
        float y = DeckView.Y0;
        Ui.card(c, RACK_L, y, DeckView.X1, y + 88, Ui.PANEL);
        int score = game == null ? 0 : game.score;
        float budget = game == null ? 100 : game.budget;
        Ui.bold(c, Ui.number(score), RACK_L + 24, y + 62, 48, Ui.TEXT);
        Ui.mono(c, "BEST " + Ui.number(Math.max(score, best(deck))), RACK_L + 280, y + 56, 24, Ui.DIM);
        if (game != null) {
            Ui.mono(c, "LEVEL " + game.level() + "   COMBO x" + game.combo, RACK_L + 560, y + 56, 24, Ui.AMBER);
        }
        Ui.right(c, String.format(Locale.US, "ERROR BUDGET %.0f%%", budget), DeckView.X1 - 340, y + 56, 24,
                budget < 30 ? Ui.RED : Ui.GREEN);
        Ui.bar(c, DeckView.X1 - 320, y + 30, DeckView.X1 - 24, y + 58, budget / 100f, budget < 30 ? Ui.RED : Ui.GREEN);
    }

    private void drawRack(Canvas c, long now) {
        for (int i = 0; i < IncidentGame.COLS * IncidentGame.ROWS; i++) {
            float l = RACK_L + (i % IncidentGame.COLS) * CELL_W;
            float t = RACK_T + (i / IncidentGame.COLS) * CELL_H;
            float r = l + CELL_W - 14;
            float b = t + CELL_H - 14;
            int state = game == null ? IncidentGame.IDLE : game.state[i];
            float flash = (float) (Math.sin(now / (state == IncidentGame.DOWN ? 90.0 : 150.0) + i) * 0.5 + 0.5);
            Ui.card(c, l, t, r, b, fill(state, flash));
            String host = HOSTS[i % HOSTS.length] + "-" + (i / IncidentGame.COLS + 1);
            Ui.mono(c, host, l + 16, t + 34, 22, state == IncidentGame.IDLE ? Ui.FAINT : Ui.TEXT);
            Ui.centre(c, word(state, game == null ? 0 : game.reboots[i]), (l + r) / 2, (t + b) / 2 + 18, 30, wordColor(state));
            if (game != null && (state == IncidentGame.WARN || state == IncidentGame.CRIT)) {
                float limit = state == IncidentGame.WARN ? 4f : 3f;
                Ui.bar(c, l + 16, b - 24, r - 16, b - 14, Math.min(1, game.timer[i] / limit), wordColor(state));
            }
            if (game != null && !game.over && focused && game.cursor == i) {
                Ui.focus(c, l, t, r, b, Ui.TEXT, now);
            }
        }
    }

    private static int fill(int state, float flash) {
        switch (state) {
            case IncidentGame.WARN: return 0xFF3A2C10;
            case IncidentGame.CRIT: return flash > 0.5f ? 0xFF5A1626 : 0xFF3F1020;
            case IncidentGame.DOWN: return flash > 0.5f ? 0xFF8C1830 : 0xFF2A0A12;
            case IncidentGame.GHOST: return 0xFF2A1A44;
            default: return Ui.PANEL;
        }
    }

    private static String word(int state, int reboots) {
        switch (state) {
            case IncidentGame.WARN: return "WARN";
            case IncidentGame.CRIT: return "CRITICAL";
            case IncidentGame.DOWN: return "DOWN x" + reboots;
            case IncidentGame.GHOST: return "FLAPPING";
            default: return "OK";
        }
    }

    private static int wordColor(int state) {
        switch (state) {
            case IncidentGame.WARN: return Ui.AMBER;
            case IncidentGame.CRIT:
            case IncidentGame.DOWN: return Ui.RED;
            case IncidentGame.GHOST: return Ui.MAGENTA;
            default: return Ui.LINE;
        }
    }

    private void drawHelp(Canvas c) {
        float l = RACK_L + 1254;
        float r = DeckView.X1;
        float t = RACK_T;
        Ui.card(c, l, t, r, DeckView.Y1 - 14, Ui.PANEL);
        Ui.heading(c, "RUNBOOK", l + 22, t + 46);
        float y = t + 96;
        y = rule(c, "WARN", "OK fixes it, 10 points", Ui.AMBER, l, r, y);
        y = rule(c, "CRITICAL", "OK fixes it, 25 points. Riskier, pays more", Ui.RED, l, r, y);
        y = rule(c, "DOWN", "OK three times to reboot. Burns budget until it is back", Ui.RED, l, r, y);
        y = rule(c, "FLAPPING", "A false alarm. Leave it alone: it costs 6% budget to touch", Ui.MAGENTA, l, r, y);
        Ui.para(c, "Long combos multiply your score, up to x5. A miss resets the combo.", l + 22, y + 12, r - l - 44, 23, Ui.DIM, Ui.SANS, 4);
    }

    private static float rule(Canvas c, String name, String text, int color, float l, float r, float y) {
        Ui.bold(c, name, l + 22, y, 25, color);
        return Ui.para(c, text, l + 22, y + 34, r - l - 44, 23, Ui.TEXT, Ui.SANS, 3) + 18;
    }

    private void banner(Canvas c, String title, String detail, int color) {
        float cx = RACK_L + 620;
        float cy = RACK_T + 400;
        Ui.card(c, cx - 540, cy - 100, cx + 540, cy + 100, 0xF0060A12);
        Ui.centre(c, title, cx, cy - 10, 54, color);
        Ui.TXT.setTypeface(Ui.SANS);
        Ui.TXT.setTextAlign(android.graphics.Paint.Align.CENTER);
        Ui.TXT.setTextSize(27);
        Ui.TXT.setColor(Ui.TEXT);
        c.drawText(detail, cx, cy + 50, Ui.TXT);
        Ui.TXT.setTextAlign(android.graphics.Paint.Align.LEFT);
    }
}
