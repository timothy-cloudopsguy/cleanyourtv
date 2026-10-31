package io.github.timothy_cloudopsguy.opsdeck;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

import java.util.Collections;
import java.util.List;

/** What the panels share: the feeds, the noises, the installed apps and the way to act on them. */
final class Deck {
    static final class App {
        final String label;
        final String packageName;
        final Bitmap banner;

        App(String label, String packageName, Bitmap banner) {
            this.label = label;
            this.packageName = packageName;
            this.banner = banner;
        }
    }

    interface Actions {
        void openPanel(int index);
        void launch(String packageName);
        /** Opens a link in the reader; says so when it cannot. */
        void openLink(String url);
    }

    final Store store;
    final Sfx sfx;
    final SharedPreferences prefs;
    final Actions actions;
    volatile List<App> apps = Collections.emptyList();

    Deck(Store store, Sfx sfx, SharedPreferences prefs, Actions actions) {
        this.store = store;
        this.sfx = sfx;
        this.prefs = prefs;
        this.actions = actions;
    }
}
