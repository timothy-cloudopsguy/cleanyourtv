package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Canvas;

/** One screen of the deck. */
abstract class Panel {
    final Deck deck;
    /** True while the keys go to this panel rather than the rail. */
    boolean focused;

    Panel(Deck deck) {
        this.deck = deck;
    }

    abstract String railName();

    abstract String title();

    abstract int accent();

    abstract void draw(Canvas c, long now);

    /** A key while focused; false hands it back, and Left then returns to the rail. */
    abstract boolean key(int key);

    /** Back while focused; true when the panel used it, for example to close its own overlay. */
    boolean back() {
        return false;
    }

    /** Another panel is taking over the screen. */
    void onLeave() {}

    void onShown() {}

    void onHidden() {}
}
