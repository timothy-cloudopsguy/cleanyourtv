package io.github.timothy_cloudopsguy.opsdeck;

import java.util.Random;

/**
 * Incident Commander, the rules alone: a rack of servers raises alerts faster and faster. Fix a warning before it
 * turns critical, a critical before the server goes down, and reboot what went down (three presses) because every
 * downed server burns the error budget. Ghost alerts are false alarms: leave them, they fade by themselves.
 */
final class IncidentGame {
    static final int COLS = 6;
    static final int ROWS = 4;

    static final int IDLE = 0;
    static final int WARN = 1;
    static final int CRIT = 2;
    static final int DOWN = 3;
    static final int GHOST = 4;

    /** What the last press or update did, for the sound. */
    static final int EVENT_NONE = 0;
    static final int EVENT_FIXED = 1;
    static final int EVENT_MISS = 2;
    static final int EVENT_WENT_DOWN = 3;
    static final int EVENT_OVER = 4;

    private static final int REBOOT_PRESSES = 3;
    private static final float BURN_PER_SERVER = 2.5f;
    private static final float GHOST_PENALTY = 6f;

    final int[] state = new int[COLS * ROWS];
    final float[] timer = new float[COLS * ROWS];
    final int[] reboots = new int[COLS * ROWS];
    int cursor;
    int score;
    int combo;
    int resolved;
    float budget = 100f;
    float elapsed;
    boolean over;

    private final Random random;
    private float spawnIn = 0.6f;

    IncidentGame(Random random) {
        this.random = random;
    }

    int level() {
        return 1 + (int) (elapsed / 20f);
    }

    int downCount() {
        int n = 0;
        for (int s : state) if (s == DOWN) n++;
        return n;
    }

    /** Advance by dt seconds; returns the loudest event that happened. */
    int update(float dt) {
        if (over) return EVENT_NONE;
        elapsed += dt;
        int event = EVENT_NONE;
        spawnIn -= dt;
        if (spawnIn <= 0) {
            spawn();
            float gap = Math.max(0.35f, 1.6f - 0.12f * level());
            spawnIn = gap * (0.7f + 0.6f * random.nextFloat());
        }
        for (int i = 0; i < state.length; i++) {
            int s = state[i];
            if (s == IDLE || s == DOWN) continue;
            timer[i] -= dt;
            if (timer[i] > 0) continue;
            if (s == WARN) {
                state[i] = CRIT;
                timer[i] = critSeconds();
            } else if (s == CRIT) {
                state[i] = DOWN;
                reboots[i] = REBOOT_PRESSES;
                combo = 0;
                event = EVENT_WENT_DOWN;
            } else {
                state[i] = IDLE;
            }
        }
        budget -= downCount() * BURN_PER_SERVER * dt;
        if (budget <= 0) {
            budget = 0;
            over = true;
            return EVENT_OVER;
        }
        return event;
    }

    /** OK on the server under the cursor. */
    int press() {
        if (over) return EVENT_NONE;
        int i = cursor;
        switch (state[i]) {
            case WARN:
                award(10);
                state[i] = IDLE;
                return EVENT_FIXED;
            case CRIT:
                award(25);
                state[i] = IDLE;
                return EVENT_FIXED;
            case DOWN:
                if (--reboots[i] > 0) return EVENT_FIXED;
                award(15);
                state[i] = IDLE;
                return EVENT_FIXED;
            case GHOST:
                combo = 0;
                state[i] = IDLE;
                budget = Math.max(0, budget - GHOST_PENALTY);
                if (budget == 0) {
                    over = true;
                    return EVENT_OVER;
                }
                return EVENT_MISS;
            default:
                combo = 0;
                return EVENT_MISS;
        }
    }

    /** Move the cursor; false when that would leave the rack on that side. */
    boolean move(int dx, int dy) {
        int x = cursor % COLS + dx;
        int y = cursor / COLS + dy;
        if (x < 0 || x >= COLS || y < 0 || y >= ROWS) return false;
        cursor = y * COLS + x;
        return true;
    }

    private void award(int points) {
        combo++;
        resolved++;
        score += points * Math.min(5, 1 + combo / 5);
    }

    private void spawn() {
        for (int tries = 0; tries < 8; tries++) {
            int i = random.nextInt(state.length);
            if (state[i] != IDLE) continue;
            if (level() >= 2 && random.nextFloat() < 0.15f) {
                state[i] = GHOST;
                timer[i] = 2.5f;
            } else {
                state[i] = WARN;
                timer[i] = warnSeconds();
            }
            return;
        }
    }

    private float warnSeconds() {
        return Math.max(1.6f, 4f - 0.2f * level());
    }

    private float critSeconds() {
        return Math.max(1.2f, 3f - 0.15f * level());
    }
}
