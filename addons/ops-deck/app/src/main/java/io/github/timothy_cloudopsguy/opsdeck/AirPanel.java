package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A live traffic map on the D-pad, where the arrows have one job in each mode. On the map they move it; OK opens the menu
 * (zoom, home, next priority aircraft) and OK again starts picking. Picking, they hop a highlight between aircraft and OK
 * watches the one highlighted. Watching, they hop to a neighbour and Back returns to picking. The channel or fast-forward
 * keys zoom directly. Keys stay here until Back, since Left has to do its job in every mode.
 */
final class AirPanel extends Panel {
    private static final int MIN_ZOOM = 3;
    private static final int MAX_ZOOM = 12;
    private static final int START_ZOOM = 8;
    private static final int WIDE_ZOOM = 4;
    private static final double WIDE_LAT = 39;
    private static final double WIDE_LON = -98;
    /** Screen units for one tile at a whole zoom: above 256 so map labels can be read across a room. */
    private static final double TILE_PX = 360;
    private static final float L = DeckView.X0;
    private static final float T = DeckView.Y0;
    private static final float R = DeckView.X1;
    private static final float B = DeckView.Y1;
    private static final float MX = (L + R) / 2;
    private static final float MY = (T + B) / 2;
    private static final float PAN_STEP = 260f;
    private static final int GLYPH_LIMIT = 450;
    private static final int LABEL_LIMIT = 60;
    private static final int LABEL_MIN_ZOOM = 8;
    private static final int TRAIL_MAX = 240;
    private static final int DASHES = 32;
    // Split where traffic really is, from a world snapshot: under 1% flies above 45,000 ft but about a quarter above
    // 36,000. 10,000 ft is where a cabin's rules change.
    private static final int[] ALT_FEET = {5_000, 10_000, 20_000, 30_000, 36_000};
    private static final int[] ALT_COLOR = {0xFFFF8A3D, Ui.AMBER, Ui.GREEN, Ui.CYAN, Ui.BLUE, Ui.MAGENTA};
    private static final String[] ALT_NAME = {"<5k", "<10k", "<20k", "<30k", "<36k", "36k+"};
    /** Away from every altitude colour and from emergency red, and always drawn on top. */
    private static final int MILITARY = 0xFFFFF200;
    private static final int ESTIMATE = 0xC0E8F1FA;
    private static final float MILITARY_SCALE = 1.2f;
    private static final float MILITARY_WIDE_SCALE = 0.7f;
    /** Outline units fit within 32 either side of the middle; drawn at twice that so scaling stays sharp. */
    private static final int SPRITE_PX = 128;
    private static final float SPRITE_SCALE = 2f;
    // The foot of the flight card: the graph across it, and under that the aircraft beside the QR code.
    private static final float MODEL_W = 225;
    private static final float MODEL_H = 168;
    private static final float TILE_L = R - 448;
    private static final float TILE_B = B - 70;
    private static final float TILE_T = TILE_B - MODEL_H;
    private static final float GRAPH_W = 410;
    private static final float GRAPH_H = 96;
    private static final float GRAPH_B = TILE_T - 10;
    /** Exactly four pixels a module for the usual link here, so it stays as sharp as a larger code. */
    private static final float QR_SIDE = 148;
    private static final long QR_SWAP_MS = 8_000;
    private static final int GRAPH_SLICES = 92;
    /** A held arrow repeats far faster than anyone can follow a highlight. */
    private static final long HOP_REPEAT_MS = 220;
    /** Fetching a path and route for every aircraft hopped past would soon use up the path feed's daily limit. */
    private static final long WATCH_SETTLE_MS = 2_000;
    private static final int LOST_AFTER_REFRESHES = 2;
    private static final float RECENTRE_MARGIN = 40;
    private static final long LABEL_REFRESH_MS = 5_000;
    private static final float[][] COVERED_ON_MAP = {
            {L + 14, T + 14, L + 590, T + 110},
            {L + 14, B - 104, L + 800, B - 12},
            {R - 440, B - 40, R, B},
    };
    private static final float[][] COVERED_WATCHING = {
            {L + 14, T + 14, L + 590, T + 110},
            {L + 14, B - 104, L + 800, B - 12},
            {R - 470, T + 14, R, B},
    };

    private static final int VIEW_MAP = 0;
    private static final int VIEW_CAMS = 1;
    private static final int VIEW_WALL = 2;
    private static final String[] VIEW_NAMES = {"MAP", "CAMS", "WALL"};

    private final AirFeed feed;
    private final CamBoard board;
    private int view = VIEW_MAP;
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dest = new RectF();
    // Rotating a filled path costs far more per frame than a bitmap, and hundreds are drawn: each outline is drawn once
    // into one-channel bitmaps, about 16 KB each, and tinted by the paint.
    private final Bitmap[] solid = new Bitmap[Aircraft.KINDS];
    private final Bitmap[] hollow = new Bitmap[Aircraft.KINDS];
    private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint modelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashedRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect photoCrop = new Rect();
    /** What this app has recorded itself, for when OpenSky has no path to give. */
    private AirData.Track liveTrack;
    private AirData.Track profileFor;
    private AirData.Profile profile;
    private final float[] speedLine = new float[(GRAPH_SLICES - 1) * 4];
    private final Path face = new Path();
    private Aircraft.Mesh model;
    private int modelKind = -1;
    private float[] turned;
    private float[] depths;
    private int[] order;
    private final Path trailPath = new Path();
    private final float[][] dots = new float[ALT_COLOR.length + 2][];
    private final int[] dotCount = new int[ALT_COLOR.length + 2];
    private final List<double[]> trail = new ArrayList<>();
    private final List<AirData.Plane> alarms = new ArrayList<>();

    private double centerX = AirData.worldX(WIDE_LON);
    private double centerY = AirData.worldY(WIDE_LAT);
    private double shownX = centerX;
    private double shownY = centerY;
    private int zoom = WIDE_ZOOM;
    private double shownZoom = WIDE_ZOOM;
    private boolean movedByHand;
    private boolean sentHome;
    private boolean controls;
    /** The menu's second page. */
    private boolean moreMenu;
    /** Hides everything but military aircraft, and emergencies, which are never hidden. */
    private boolean militaryOnly;
    private boolean picking;
    /** The aircraft highlighted while picking; null once it has been lost. */
    private AirData.Plane target;
    private int targetVersion = -1;
    private int targetMissed;
    /** Where a lost target was last known: shown, and the arrows hop on from there. */
    private AirData.Plane lostTarget;
    private String labelName = "";
    private String labelLine = "";
    private int labelColor = Ui.DIM;
    private long labelAt;
    private long lastHop;
    private String hoppedFrom = "";
    private int hopDx;
    private int hopDy;
    private AirData.Plane selected;
    private int selectedMissed;
    private boolean selectedLost;
    private long watchedSince;
    private AirData.RouteCheck routeCheck;
    private int routeCheckVersion = -1;
    private boolean routeSettled;
    private int selectedVersion = -1;
    private boolean follow;
    private String qrFor = "";
    private boolean[][] qr;
    private String photoQrFor = "";
    private boolean[][] photoQr;
    private int inView;
    private int militaryInView;
    private int estimatesInView;
    private int emergencies;
    private String emergencyName = "";
    private double[][] toGo;
    private String toGoFor = "";
    /** The emergency code that led here, kept so a feed that disagrees is shown rather than silently overruling it. */
    private String reportedSquawk = "";
    private int toGoVersion = -1;

    AirPanel(Deck deck, Context context) {
        super(deck);
        feed = new AirFeed(context, deck.prefs, deck.store.estimates, context.getString(io.github.timothy_cloudopsguy.opsdeck.R.string.home_lat),
                context.getString(io.github.timothy_cloudopsguy.opsdeck.R.string.home_lon));
        board = new CamBoard(deck, context, AirData.SPOTTER_CAMS, "spotter_wall", AirData.SPOTTER_START_WALL, Ui.BLUE);
        for (int i = 0; i < dots.length; i++) dots[i] = new float[4096];
        tilePaint.setColorFilter(new ColorMatrixColorFilter(darkMap()));
        for (int kind = 0; kind < solid.length; kind++) {
            Path outline = glyph(kind);
            solid[kind] = sprite(outline, Paint.Style.FILL);
            hollow[kind] = sprite(outline, Paint.Style.STROKE);
        }
        modelPaint.setStyle(Paint.Style.FILL);
        dashedRing.setStyle(Paint.Style.STROKE);
        dashedRing.setStrokeWidth(5);
        dashedRing.setColor(Ui.TEXT);
        dashedRing.setPathEffect(new DashPathEffect(new float[]{14, 10}, 0));
    }

    private static Bitmap sprite(Path outline, Paint.Style style) {
        Bitmap bitmap = Bitmap.createBitmap(SPRITE_PX, SPRITE_PX, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(SPRITE_PX / 2f, SPRITE_PX / 2f);
        canvas.scale(SPRITE_SCALE, SPRITE_SCALE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(style);
        paint.setStrokeWidth(1.2f);
        canvas.drawPath(outline, paint);
        return bitmap;
    }

    /** The class's outline, mirrored about the nose-to-tail axis, with its engines or rotor. */
    private static Path glyph(int kind) {
        float[] half = Aircraft.outline(kind);
        Path path = new Path();
        path.moveTo(half[0], half[1]);
        for (int i = 2; i < half.length; i += 2) path.lineTo(half[i], half[i + 1]);
        for (int i = half.length - 4; i >= 2; i -= 2) path.lineTo(-half[i], half[i + 1]);
        path.close();
        float[] pods = Aircraft.pods(kind);
        for (int i = 0; i < pods.length; i += 4) {
            path.addRect(pods[i] - pods[i + 2], pods[i + 1] - pods[i + 3], pods[i] + pods[i + 2], pods[i + 1] + pods[i + 3], Path.Direction.CW);
            if (pods[i] != 0) {
                path.addRect(-pods[i] - pods[i + 2], pods[i + 1] - pods[i + 3], -pods[i] + pods[i + 2], pods[i + 1] + pods[i + 3], Path.Direction.CW);
            }
        }
        return path;
    }

    /** OpenStreetMap's bright map turned night-mode: mostly grey, inverted, then dimmed toward the deck's blue. */
    private static ColorMatrix darkMap() {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0.25f);
        matrix.postConcat(new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0}));
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.62f, 0.72f, 0.92f, 1f);
        matrix.postConcat(dim);
        return matrix;
    }

    @Override String railName() { return "AIR"; }
    @Override String title() { return "AIR TRAFFIC"; }
    @Override int accent() { return Ui.BLUE; }

    @Override
    boolean key(int key) {
        if (view != VIEW_MAP) return camsKey(key);
        if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            zoomBy(1);
            recentreTarget();
            return true;
        }
        if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
            zoomBy(-1);
            recentreTarget();
            return true;
        }
        if (controls) return controlKey(key);
        if (Ui.isOk(key)) {
            if (picking) {
                if (target != null) watch(target);
                else deck.sfx.tick();
                return true;
            }
            controls = true;
            deck.sfx.tick();
            return true;
        }
        if (picking || selected != null) return hopKey(key);
        double world = TILE_PX * Math.pow(2, zoom);
        double step = PAN_STEP / world;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                pan(-step, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                pan(step, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                pan(0, -step);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                pan(0, step);
                return true;
            default:
                return false;
        }
    }

    private boolean moreKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                goHome();
                moreMenu = false;
                controls = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                militaryOnly = !militaryOnly;
                moreMenu = false;
                controls = false;
                deck.sfx.confirm();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moreMenu = false;
                controls = false;
                view = VIEW_CAMS;
                deck.sfx.confirm();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                if (Ui.isOk(key)) {
                    moreMenu = false;
                    deck.sfx.tick();
                    return true;
                }
                return false;
        }
    }

    /** The spotter cameras' tabs: Left and Right walk from the map to the cameras to the wall. */
    private boolean camsKey(int key) {
        if (board.overlayKey(key)) return true;
        if (view == VIEW_CAMS) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) return moveView(VIEW_MAP);
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return moveView(VIEW_WALL);
            return board.camsKey(key);
        }
        return board.wallKey(key) || (key == KeyEvent.KEYCODE_DPAD_LEFT && moveView(VIEW_CAMS));
    }

    private boolean moveView(int next) {
        view = next;
        deck.sfx.tick();
        return true;
    }

    private boolean controlKey(int key) {
        if (moreMenu) return moreKey(key);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                zoomBy(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                zoomBy(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moreMenu = true;
                deck.sfx.tick();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                selectPriority();
                controls = false;
                return true;
            default:
                if (Ui.isOk(key)) {
                    if (selected == null) startPicking();
                    else {
                        controls = false;
                        deck.sfx.tick();
                    }
                    return true;
                }
                return false;
        }
    }

    private boolean hopKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                hopBy(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                hopBy(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                hopBy(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                hopBy(0, 1);
                return true;
            default:
                return false;
        }
    }

    private AirData.PickView pickView() {
        boolean shapesOnly = inView > GLYPH_LIMIT || militaryOnly;
        return new AirData.PickView(centerX, centerY, TILE_PX * Math.pow(2, zoom), L, T, R, B, shapesOnly,
                selected != null ? COVERED_WATCHING : COVERED_ON_MAP);
    }

    /** From the menu; stays open if nothing can be picked, as its centre slot then says to zoom in. */
    private void startPicking() {
        long now = System.currentTimeMillis();
        AirData.Plane start = AirData.pickStart(feed.planes, pickView(), now);
        if (start == null) {
            deck.sfx.tick();
            return;
        }
        controls = false;
        picking = true;
        hoppedFrom = "";
        setTarget(start, now);
        deck.sfx.confirm();
    }

    private void setTarget(AirData.Plane plane, long now) {
        target = plane;
        lostTarget = null;
        targetVersion = feed.version;
        targetMissed = 0;
        refreshLabel(now);
    }

    private void hopBy(int dx, int dy) {
        long now = System.currentTimeMillis();
        if (now - lastHop < HOP_REPEAT_MS) return;
        lastHop = now;
        AirData.Plane from = picking ? (target != null ? target : lostTarget) : selected;
        if (from == null) return;
        AirData.PickView view = pickView();
        AirData.Plane next = null;
        // Straight back undoes the last hop, which the nearest-in-that-direction rule would not always do.
        if (dx == -hopDx && dy == -hopDy && !hoppedFrom.isEmpty()) {
            AirData.Plane previous = AirData.find(feed.planes, hoppedFrom);
            if (previous != null && view.eligible(previous, now)) next = previous;
        }
        if (next == null) next = AirData.hop(feed.planes, from, dx, dy, view, now);
        if (next == null) {
            deck.sfx.tick();
            return;
        }
        hoppedFrom = from.hex;
        hopDx = dx;
        hopDy = dy;
        if (picking) {
            setTarget(next, now);
            deck.sfx.tick();
        } else {
            setWatched(next, now);
            deck.sfx.tick();
        }
    }

    /** Lost or moved by a zoom: keep what is being picked on screen. */
    private void recentreTarget() {
        if (!picking || target == null) return;
        long now = System.currentTimeMillis();
        centerX = AirData.wrapX(target.xAt(now));
        centerY = AirData.clampY(target.yAt(now));
    }

    private void refreshLabel(long now) {
        AirData.Plane p = target;
        if (p == null) return;
        labelAt = now;
        labelName = p.name();
        if (p.emergency()) {
            labelLine = p.squawk + " " + AirData.squawkMeaning(p.squawk).toUpperCase(Locale.ROOT);
            labelColor = Ui.RED;
        } else if (p.estimated) {
            labelLine = "ESTIMATED, HEARD " + Parsers.ago(p.timeMs, now).toUpperCase(Locale.ROOT) + " AGO";
            labelColor = Ui.AMBER;
        } else {
            // A missing altitude reads as 0, so nothing is said rather than something wrong.
            String altitude = p.altitudeFeet > 0 ? String.format(Locale.US, "%,d FT", p.altitudeFeet) : "";
            labelLine = (p.military ? "MIL  " : "") + join(p.type, altitude);
            labelColor = p.military ? MILITARY : Ui.TEXT;
        }
    }

    @Override
    boolean back() {
        if (view != VIEW_MAP) {
            if (!board.back()) view = VIEW_MAP;
            return true;
        }
        if (moreMenu) {
            moreMenu = false;
            return true;
        }
        if (controls) {
            controls = false;
            return true;
        }
        if (selected != null) {
            // Back to browsing from the same aircraft, with the map left where it is.
            AirData.Plane last = selected;
            boolean lost = selectedLost;
            endWatch();
            picking = true;
            hoppedFrom = "";
            if (lost) {
                target = null;
                lostTarget = last;
            } else {
                setTarget(last, System.currentTimeMillis());
            }
            return true;
        }
        if (picking) {
            picking = false;
            target = null;
            lostTarget = null;
            return true;
        }
        return false;
    }

    private void endWatch() {
        selected = null;
        selectedLost = false;
        follow = false;
        trail.clear();
    }

    private void pan(double dx, double dy) {
        centerX = AirData.wrapX(centerX + dx);
        centerY = AirData.clampY(centerY + dy);
        movedByHand = true;
        follow = false;
        deck.sfx.tick();
    }

    private void zoomBy(int by) {
        int next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + by));
        if (next == zoom) return;
        zoom = next;
        movedByHand = true;
        deck.sfx.app(zoom);
    }

    private void goHome() {
        double[] home = feed.home;
        centerX = AirData.worldX(home == null ? WIDE_LON : home[1]);
        centerY = AirData.worldY(home == null ? WIDE_LAT : home[0]);
        zoom = home == null ? WIDE_ZOOM : START_ZOOM;
        endWatch();
        deck.sfx.confirm();
    }

    /** Emergencies, then military, then whatever is nearest: from anywhere, this is the way to the one the banner names. */
    private void selectPriority() {
        long now = System.currentTimeMillis();
        String current = selected == null ? "" : selected.hex;
        List<AirData.Plane> all = new ArrayList<>(alarms);
        all.addAll(feed.planes);
        AirData.Plane next = AirData.nextPriority(all, current);
        if (next == null && !militaryOnly) next = AirData.nearest(feed.planes, centerX, centerY, now, current);
        if (next == null) {
            deck.sfx.tick();
            return;
        }
        watch(next);
    }

    private void watch(AirData.Plane plane) {
        picking = false;
        target = null;
        lostTarget = null;
        hoppedFrom = "";
        // Close enough for the live feed, which has the type, registration and frequent updates.
        zoom = Math.max(zoom, AirFeed.REGIONAL_MIN_ZOOM);
        setWatched(plane, System.currentTimeMillis());
        deck.sfx.confirm();
    }

    private void setWatched(AirData.Plane plane, long now) {
        selected = plane;
        selectedLost = false;
        selectedMissed = 0;
        reportedSquawk = plane.emergency() ? plane.squawk : "";
        selectedVersion = feed.version;
        follow = true;
        watchedSince = now;
        liveTrack = feed.history.track(plane.hex);
        routeCheck = null;
        routeCheckVersion = -1;
        routeSettled = false;
        toGoFor = "";
        trail.clear();
        trail.add(new double[]{plane.xAt(now), plane.yAt(now)});
    }

    /**
     * The route for the aircraft being watched, tested against where it is. A clear answer is kept for this aircraft, so
     * a turn does not make the route flip or vanish; an unclear one is tried again as new positions come in.
     */
    private AirData.RouteCheck checkedRoute(long now) {
        if (selected == null || !watchSettled(now)) return null;
        if (routeSettled || routeCheckVersion == feed.version) return routeCheck;
        AirData.RouteSet set = feed.routes(selected.callsign);
        if (set == null) return null;
        routeCheckVersion = feed.version;
        routeCheck = AirData.checkRoute(set.legs, AirData.lat(selected.yAt(now)), AirData.lon(selected.xAt(now)), selected.track, selected.climbFpm);
        routeSettled = routeCheck.trusted() && routeCheck.sure;
        toGoFor = "";
        return routeCheck;
    }

    private boolean watchSettled(long now) {
        return now - watchedSince >= WATCH_SETTLE_MS;
    }

    @Override
    void onLeave() {
        board.stop();
    }

    private void drawCamsView(Canvas c, long now) {
        board.focused = focused;
        board.sync(view == VIEW_WALL);
        if (board.showingFullScreen()) {
            board.drawLarge(c, now);
            return;
        }
        float x = L;
        for (int i = 0; i < VIEW_NAMES.length; i++) {
            boolean on = i == view;
            Ui.card(c, x, 118, x + 200, 170, on ? Ui.BLUE : Ui.PANEL);
            Ui.centre(c, i == VIEW_WALL ? "WALL " + board.wallSize() : VIEW_NAMES[i], x + 100, 154, 25, on ? Ui.BG : Ui.DIM);
            x += 212;
        }
        if (view == VIEW_CAMS) board.drawCams(c, now);
        else board.drawWall(c, now);
        board.drawOverlays(c, now);
    }

    @Override
    void draw(Canvas c, long now) {
        if (view != VIEW_MAP) {
            drawCamsView(c, now);
            return;
        }
        // Leaving the wall for the map must stop its video too.
        if (!deck.liveKey.isEmpty()) board.stop();
        settleHome();
        trackSelected(now);
        trackTarget(now);
        animate();
        // Following keeps the live feed around the aircraft however far out the map is, to see its whole path.
        feed.poll(AirData.lat(centerY), AirData.lon(centerX), follow ? Math.max(zoom, AirFeed.REGIONAL_MIN_ZOOM) : zoom, now);

        int saved = c.save();
        c.clipRect(L, T, R, B);
        double world = TILE_PX * Math.pow(2, shownZoom);
        drawTiles(c, world, now);
        drawHome(c, world);
        drawRoute(c, world, now);
        drawPlanes(c, world, now);
        // Over the other aircraft, which are dimmed while one is selected: this is what was asked to be seen.
        drawTrack(c, world, now);
        drawTrail(c, world);
        drawSelected(c, world, now);
        drawEmergencies(c, world, now);
        drawCrosshair(c);
        c.restoreToCount(saved);
        drawHud(c, now);
        if (picking) drawTarget(c, world, now);
        if (selected != null) {
            drawCard(c, now);
            drawProfile(c);
            if (watchSettled(now)) feed.wantPhoto(selected.hex, selected.registration, selected.description);
            if (feed.photo != null && watchSettled(now)) drawPhoto(c, feed.photo);
            else drawModel(c, now);
        }
        if (controls) drawControls(c);
    }

    /** Jumps to the TV's location when it arrives, unless the owner has already gone somewhere. */
    private void settleHome() {
        double[] home = feed.home;
        if (sentHome || home == null) return;
        sentHome = true;
        if (movedByHand) return;
        centerX = shownX = AirData.worldX(home[1]);
        centerY = shownY = AirData.worldY(home[0]);
        zoom = START_ZOOM;
        shownZoom = START_ZOOM;
    }

    private void trackSelected(long now) {
        if (selected == null) return;
        if (selectedVersion != feed.version) {
            selectedVersion = feed.version;
            AirData.Plane fresh = null;
            for (AirData.Plane p : feed.planes) {
                if (p.hex.equals(selected.hex)) {
                    fresh = p;
                    break;
                }
            }
            if (fresh != null) {
                selected = fresh;
                selectedMissed = 0;
                selectedLost = false;
                follow = true;
                trail.add(new double[]{fresh.worldX, fresh.worldY});
                if (trail.size() > TRAIL_MAX) trail.remove(0);
                liveTrack = feed.history.track(fresh.hex);
            } else if (++selectedMissed >= LOST_AFTER_REFRESHES) {
                // Said out loud, rather than following a position that is no longer being reported.
                selectedLost = true;
                follow = false;
            }
        }
        if (follow) {
            centerX = AirData.wrapX(selected.xAt(now));
            centerY = AirData.clampY(selected.yAt(now));
        }
    }

    /** Follows the highlighted aircraft through refreshes. It never moves to another by itself: if lost, that is shown. */
    private void trackTarget(long now) {
        if (!picking || target == null) return;
        if (targetVersion != feed.version) {
            targetVersion = feed.version;
            AirData.Plane fresh = AirData.find(feed.planes, target.hex);
            if (fresh != null) {
                target = fresh;
                targetMissed = 0;
                refreshLabel(now);
            } else if (++targetMissed >= LOST_AFTER_REFRESHES) {
                lostTarget = target;
                target = null;
                return;
            }
        }
        if (now - labelAt >= LABEL_REFRESH_MS) refreshLabel(now);
        double world = TILE_PX * Math.pow(2, zoom);
        float x = (float) (MX + AirData.wrapDelta(target.xAt(now) - centerX) * world);
        float y = (float) (MY + (target.yAt(now) - centerY) * world);
        if (x < L + RECENTRE_MARGIN || x > R - RECENTRE_MARGIN || y < T + RECENTRE_MARGIN || y > B - RECENTRE_MARGIN) recentreTarget();
    }

    private void animate() {
        shownX = AirData.wrapX(shownX + AirData.wrapDelta(centerX - shownX) * 0.25);
        shownY += (centerY - shownY) * 0.25;
        shownZoom += (zoom - shownZoom) * 0.2;
        if (Math.abs(zoom - shownZoom) < 0.002) shownZoom = zoom;
    }

    private float screenX(double x, double world) {
        return (float) (MX + AirData.wrapDelta(x - shownX) * world);
    }

    private float screenY(double y, double world) {
        return (float) (MY + (y - shownY) * world);
    }

    private void drawTiles(Canvas c, double world, long now) {
        Ui.FILL.setColor(0xFF0A0E16);
        c.drawRect(L, T, R, B, Ui.FILL);
        int z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, (int) Math.round(shownZoom)));
        int n = 1 << z;
        int firstX = (int) Math.floor((shownX + (L - MX) / world) * n);
        int lastX = (int) Math.floor((shownX + (R - MX) / world) * n);
        int firstY = Math.max(0, (int) Math.floor((shownY + (T - MY) / world) * n));
        int lastY = Math.min(n - 1, (int) Math.floor((shownY + (B - MY) / world) * n));
        double tile = world / n;
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                Bitmap bitmap = feed.tile(z, Math.floorMod(x, n), y, now);
                if (bitmap == null) continue;
                float left = (float) (MX + (x / (double) n - shownX) * world);
                float top = (float) (MY + (y / (double) n - shownY) * world);
                // A hair of overlap, or rounding shows hairlines between tiles.
                dest.set(left, top, (float) (left + tile) + 0.6f, (float) (top + tile) + 0.6f);
                c.drawBitmap(bitmap, null, dest, tilePaint);
            }
        }
    }

    private void drawHome(Canvas c, double world) {
        double[] home = feed.home;
        if (home == null) return;
        float x = screenX(AirData.worldX(home[1]), world);
        float y = screenY(AirData.worldY(home[0]), world);
        Ui.STROKE.setColor(Ui.RED);
        Ui.STROKE.setStrokeWidth(3);
        c.drawCircle(x, y, 11, Ui.STROKE);
        Ui.FILL.setColor(Ui.RED);
        c.drawCircle(x, y, 4, Ui.FILL);
    }

    private void drawTrail(Canvas c, double world) {
        if (selected == null || trail.size() < 2) return;
        trailPath.rewind();
        for (int i = 0; i < trail.size(); i++) {
            double[] p = trail.get(i);
            float x = screenX(p[0], world);
            float y = screenY(p[1], world);
            if (i == 0) trailPath.moveTo(x, y);
            else trailPath.lineTo(x, y);
        }
        Ui.STROKE.setColor(Ui.TEXT);
        Ui.STROKE.setStrokeWidth(3);
        c.drawPath(trailPath, Ui.STROKE);
    }

    /** The path actually flown, coloured by altitude as the aircraft are. */
    private void drawTrack(Canvas c, double world, long now) {
        if (selected == null || !watchSettled(now)) return;
        AirData.Track track = feed.track(selected.hex, now);
        if (track == null) return;
        Ui.STROKE.setStrokeWidth(4);
        float x0 = screenX(track.xs[0], world);
        float y0 = screenY(track.ys[0], world);
        for (int i = 1; i < track.xs.length; i++) {
            float x1 = screenX(track.xs[i], world);
            float y1 = screenY(track.ys[i], world);
            // A segment across the date line would otherwise streak over the whole map.
            if (Math.abs(x1 - x0) < world / 2) {
                Ui.STROKE.setColor(altitudeColor(track.feet[i]));
                c.drawLine(x0, y0, x1, y1, Ui.STROKE);
            }
            x0 = x1;
            y0 = y1;
        }
        if (!trail.isEmpty()) {
            double[] first = trail.get(0);
            float x1 = screenX(first[0], world);
            float y1 = screenY(first[1], world);
            if (Math.abs(x1 - x0) < world / 2) c.drawLine(x0, y0, x1, y1, Ui.STROKE);
        }
    }

    /** Dashes on to the destination and a mark at each end. The route is looked up by flight number, so it is a best guess. */
    private void drawRoute(Canvas c, double world, long now) {
        AirData.RouteCheck check = checkedRoute(now);
        if (check == null || check.route == null) return;
        AirData.Route route = check.route;
        if (route.toPoint != null && check.trusted()) {
            if (!selected.hex.equals(toGoFor) || toGoVersion != feed.version) {
                toGoFor = selected.hex;
                toGoVersion = feed.version;
                toGo = AirData.greatCircle(AirData.lat(selected.yAt(now)), AirData.lon(selected.xAt(now)),
                        route.toPoint[0], route.toPoint[1], DASHES * 2);
            }
            Ui.STROKE.setColor(Ui.TEXT);
            Ui.STROKE.setStrokeWidth(3);
            for (int i = 0; i + 1 < toGo.length; i += 2) {
                float x0 = screenX(toGo[i][0], world);
                float x1 = screenX(toGo[i + 1][0], world);
                if (Math.abs(x1 - x0) < world / 2) {
                    c.drawLine(x0, screenY(toGo[i][1], world), x1, screenY(toGo[i + 1][1], world), Ui.STROKE);
                }
            }
        }
        drawAirport(c, route.fromPoint, route.from, world);
        drawAirport(c, route.toPoint, route.to, world);
    }

    private void drawAirport(Canvas c, double[] point, String name, double world) {
        if (point == null) return;
        float x = screenX(AirData.worldX(point[1]), world);
        float y = screenY(AirData.worldY(point[0]), world);
        Ui.FILL.setColor(Ui.BG);
        c.drawCircle(x, y, 11, Ui.FILL);
        Ui.STROKE.setColor(Ui.TEXT);
        Ui.STROKE.setStrokeWidth(3);
        c.drawCircle(x, y, 11, Ui.STROKE);
        int space = name.indexOf(' ');
        Ui.bold(c, space > 0 ? name.substring(0, space) : name, x + 18, y + 8, 23, Ui.TEXT);
    }

    /** Red rings and a name at every zoom, so an emergency can be found even among thousands of dots. */
    private void drawEmergencies(Canvas c, double world, long now) {
        float pulse = (now % 1200) / 1200f;
        for (int i = 0; i < alarms.size(); i++) {
            AirData.Plane p = alarms.get(i);
            float x = screenX(p.xAt(now), world);
            float y = screenY(p.yAt(now), world);
            if (x < L || x > R || y < T || y > B) continue;
            Ui.FILL.setColor(Ui.RED);
            c.drawCircle(x, y, 9, Ui.FILL);
            Ui.STROKE.setColor(Ui.RED);
            Ui.STROKE.setStrokeWidth(4);
            c.drawCircle(x, y, 16, Ui.STROKE);
            Ui.STROKE.setAlpha((int) (230 * (1 - pulse)));
            c.drawCircle(x, y, 16 + pulse * 44, Ui.STROKE);
            String label = p.name() + " " + p.squawk;
            Ui.card(c, x + 20, y - 19, x + 34 + label.length() * 14.5f, y + 18, 0xE0060A12);
            Ui.bold(c, label, x + 27, y + 8, 24, Ui.RED);
        }
    }

    private void drawPlanes(Canvas c, double world, long now) {
        List<AirData.Plane> planes = feed.planes;
        int visible = 0;
        int militaryCount = 0;
        int estimateCount = 0;
        alarms.clear();
        for (int i = 0; i < dotCount.length; i++) dotCount[i] = 0;
        // First pass decides dots or arrows, and doubles as the count the HUD shows.
        for (int i = 0; i < planes.size(); i++) {
            AirData.Plane p = planes.get(i);
            // Counted wherever it is: the banner should point to one off screen too.
            if (p.emergency()) alarms.add(p);
            if (hidden(p)) continue;
            float x = screenX(p.xAt(now), world);
            float y = screenY(p.yAt(now), world);
            if (x < L || x > R || y < T || y > B) continue;
            visible++;
            if (p.estimated) estimateCount++;
            if (p.military) {
                // Drawn as a shape at every zoom, so never one of the dots.
                militaryCount++;
                continue;
            }
            int bucket = bucket(p);
            float[] points = dots[bucket];
            int used = dotCount[bucket];
            if (used + 2 > points.length) {
                points = java.util.Arrays.copyOf(points, points.length * 2);
                dots[bucket] = points;
            }
            points[used] = x;
            points[used + 1] = y;
            dotCount[bucket] = used + 2;
        }
        for (AirData.Plane p : deck.store.emergencies) {
            boolean known = false;
            for (int i = 0; i < alarms.size() && !known; i++) known = alarms.get(i).hex.equals(p.hex);
            if (!known && p.emergency()) alarms.add(p);
        }
        inView = visible;
        militaryInView = militaryCount;
        estimatesInView = estimateCount;
        emergencies = alarms.size();
        emergencyName = alarms.isEmpty() ? "" : alarms.get(0).name() + " " + alarms.get(0).squawk;
        if (visible > GLYPH_LIMIT) {
            drawDots(c);
        } else {
            for (int i = 0; i < planes.size(); i++) {
                if (!planes.get(i).military && !hidden(planes.get(i))) drawGlyph(c, planes.get(i), world, now, visible, selected != null);
            }
        }
        for (int i = 0; i < planes.size(); i++) {
            if (planes.get(i).military) drawGlyph(c, planes.get(i), world, now, visible, selected != null);
        }
    }

    private boolean hidden(AirData.Plane p) {
        return militaryOnly && !p.military && !p.emergency();
    }

    private void drawSelected(Canvas c, double world, long now) {
        if (selected == null) return;
        drawGlyph(c, selected, world, now, 0, false);
        float x = screenX(selected.xAt(now), world);
        float y = screenY(selected.yAt(now), world);
        float pulse = (float) (Math.sin(now / 200.0) * 0.5 + 0.5);
        Ui.STROKE.setColor(Ui.TEXT);
        Ui.STROKE.setStrokeWidth(3);
        c.drawCircle(x, y, 26 + pulse * 6, Ui.STROKE);
    }

    /** The highlight and its small label. Hollow aircraft get a dashed ring: their position is a guess. */
    private void drawTarget(Canvas c, double world, long now) {
        AirData.Plane p = target != null ? target : lostTarget;
        if (p == null) return;
        float x = screenX(p.xAt(now), world);
        float y = screenY(p.yAt(now), world);
        String name;
        String line;
        int color;
        if (target != null) {
            drawGlyph(c, p, world, now, 0, false);
            Ui.STROKE.setColor(0xE0060A12);
            Ui.STROKE.setStrokeWidth(9);
            c.drawCircle(x, y, 34, Ui.STROKE);
            if (p.estimated) {
                c.drawCircle(x, y, 34, dashedRing);
            } else {
                Ui.STROKE.setColor(Ui.TEXT);
                Ui.STROKE.setStrokeWidth(5);
                c.drawCircle(x, y, 34, Ui.STROKE);
            }
            name = labelName;
            line = labelLine;
            color = labelColor;
        } else {
            name = "LOST " + p.name();
            line = "PRESS AN ARROW";
            color = Ui.AMBER;
        }
        float width = 36 + Math.max(name.length() * 18.5f, line.length() * 14.8f);
        float height = line.isEmpty() ? 52 : 88;
        float left = x + 44 + width > R - 10 ? x - 44 - width : x + 44;
        float top = Math.max(T + 10, Math.min(B - 112 - height, y - height / 2));
        Ui.card(c, left, top, left + width, top + height, 0xF0060A12);
        Ui.bold(c, name, left + 18, top + 38, 30, target != null && p.emergency() ? Ui.RED : Ui.TEXT);
        if (!line.isEmpty()) Ui.mono(c, line, left + 18, top + 72, 24, color);
    }

    private void drawDots(Canvas c) {
        Ui.FILL.setStrokeCap(Paint.Cap.ROUND);
        Ui.FILL.setStrokeWidth(5);
        for (int b = 0; b < dots.length; b++) {
            if (dotCount[b] == 0) continue;
            Ui.FILL.setColor(selected == null ? bucketColor(b) : dimmed(bucketColor(b)));
            c.drawPoints(dots[b], 0, dotCount[b] / 2, Ui.FILL);
        }
    }

    private static int dimmed(int color) {
        return (color & 0x00FFFFFF) | 0x50000000;
    }

    private void drawGlyph(Canvas c, AirData.Plane p, double world, long now, int visible, boolean dim) {
        float x = screenX(p.xAt(now), world);
        float y = screenY(p.yAt(now), world);
        if (x < L - 20 || x > R + 20 || y < T - 20 || y > B + 20) return;
        int saved = c.save();
        c.translate(x, y);
        c.rotate(p.track);
        float size = 1 / SPRITE_SCALE;
        if (p.military) size *= zoom < AirFeed.REGIONAL_MIN_ZOOM ? MILITARY_WIDE_SCALE : MILITARY_SCALE;
        c.scale(size, size);
        int color = planeColor(p);
        spritePaint.setColor(dim ? dimmed(color) : color);
        // Hollow: an estimate is a guess at where it is, not a report.
        c.drawBitmap(p.estimated ? hollow[p.kind] : solid[p.kind], -SPRITE_PX / 2f, -SPRITE_PX / 2f, spritePaint);
        c.restoreToCount(saved);
        if (!dim && visible > 0 && zoom >= LABEL_MIN_ZOOM && visible <= LABEL_LIMIT && !p.ground) {
            Ui.mono(c, p.name(), x + 20, y + 7, 19, Ui.TEXT);
        }
    }

    private static int planeColor(AirData.Plane p) {
        if (p.emergency()) return Ui.RED;
        if (p.military) return MILITARY;
        return bucketColor(bucket(p));
    }

    private static int bucket(AirData.Plane p) {
        if (p.estimated) return ALT_COLOR.length + 1;
        if (p.ground) return ALT_COLOR.length;
        return altitudeBucket(p.altitudeFeet);
    }

    private static int altitudeBucket(int feet) {
        for (int i = 0; i < ALT_FEET.length; i++) if (feet < ALT_FEET[i]) return i;
        return ALT_FEET.length;
    }

    private static int altitudeColor(int feet) {
        return ALT_COLOR[altitudeBucket(feet)];
    }

    private static int bucketColor(int bucket) {
        if (bucket > ALT_COLOR.length) return ESTIMATE;
        return bucket < ALT_COLOR.length ? ALT_COLOR[bucket] : Ui.FAINT;
    }

    private void drawCrosshair(Canvas c) {
        // Only on the map: seeing a crosshair or a ring tells which mode this is at a glance.
        if (picking || selected != null) return;
        Ui.FILL.setColor(0xB0E8F1FA);
        c.drawRect(MX - 16, MY - 1, MX - 5, MY + 1, Ui.FILL);
        c.drawRect(MX + 5, MY - 1, MX + 16, MY + 1, Ui.FILL);
        c.drawRect(MX - 1, MY - 16, MX + 1, MY - 5, Ui.FILL);
        c.drawRect(MX - 1, MY + 5, MX + 1, MY + 16, Ui.FILL);
    }

    private void drawHud(Canvas c, long now) {
        Ui.card(c, L + 14, T + 14, L + 590, T + 110, 0xE0060A12);
        Ui.bold(c, Ui.number(inView) + " IN VIEW", L + 32, T + 58, 34, Ui.TEXT);
        Ui.right(c, militaryInView + " MIL", L + 470, T + 58, 24, MILITARY);
        Ui.right(c, estimatesInView + " EST", L + 575, T + 58, 24, ESTIMATE);
        String from = feed.source.isEmpty() ? (feed.locating ? "Finding this TV..." : "Pulling traffic...")
                : feed.source + ", " + Parsers.ago(feed.updated, now) + " ago";
        Ui.mono(c, "z" + zoom + "  " + from, L + 32, T + 94, 21, Ui.DIM);
        if (militaryOnly) Ui.right(c, "MILITARY ONLY", L + 575, T + 94, 21, MILITARY);
        if (emergencies > 0) {
            Ui.card(c, L + 604, T + 14, L + 1110, T + 110, Ui.RED);
            Ui.bold(c, (emergencies == 1 ? "EMERGENCY " : emergencies + " EMERGENCIES ") + emergencyName, L + 620, T + 54, 25, Ui.BG);
            Ui.mono(c, focused ? "OK, then RIGHT, flies you there" : "Open the map to go there", L + 620, T + 90, 21, Ui.BG);
        }
        if (!feed.problem.isEmpty()) {
            Ui.card(c, L + 14, T + 122, L + 980, T + 170, 0xE0060A12);
            Ui.mono(c, feed.problem, L + 30, T + 154, 22, Ui.AMBER);
        }
        // The map's licence asks for this credit whenever it is on screen.
        Ui.card(c, R - 440, B - 40, R, B, 0xC0060A12);
        Ui.right(c, "(c) OpenStreetMap contributors", R - 12, B - 12, 19, Ui.DIM);

        float y = B - 56;
        drawModeBar(c);
        Ui.card(c, L + 14, y, L + 800, B - 12, 0xE0060A12);
        float x = L + 30;
        for (int i = 0; i < ALT_NAME.length; i++) {
            Ui.FILL.setColor(ALT_COLOR[i]);
            c.drawCircle(x + 7, y + 22, 7, Ui.FILL);
            Ui.mono(c, ALT_NAME[i], x + 20, y + 30, 19, Ui.DIM);
            x += 86;
        }
        Ui.FILL.setColor(MILITARY);
        c.drawCircle(x + 7, y + 22, 7, Ui.FILL);
        Ui.mono(c, "MIL", x + 20, y + 30, 19, Ui.DIM);
        x += 86;
        Ui.STROKE.setColor(ESTIMATE);
        Ui.STROKE.setStrokeWidth(2);
        c.drawCircle(x + 7, y + 22, 6, Ui.STROKE);
        Ui.mono(c, "EST", x + 20, y + 30, 19, Ui.DIM);
        x += 86;
    }

    /** What the arrows do right now, always in the same place. */
    private void drawModeBar(Canvas c) {
        String mode = !focused ? "AIR" : selected != null ? "WATCH" : picking ? "PICK" : "MAP";
        String hint = !focused ? "RIGHT OR OK TO FLY THE MAP"
                : selected != null ? "ARROWS HOP  OK MENU  BACK PICK"
                : picking ? (target != null ? "ARROWS NEXT AIRCRAFT  OK WATCH  BACK MAP" : "ARROWS NEXT AIRCRAFT  BACK MAP")
                : "ARROWS MOVE  OK MENU  OK OK PICK";
        float top = B - 104;
        float bottom = B - 62;
        float chip = 28 + mode.length() * 15.6f;
        Ui.card(c, L + 14, top, L + 14 + chip + 24 + hint.length() * 15.6f + 16, bottom, 0xF0060A12);
        Ui.card(c, L + 14, top, L + 14 + chip, bottom, Ui.BLUE);
        Ui.mono(c, mode, L + 28, bottom - 12, 26, Ui.BG);
        Ui.mono(c, hint, L + 14 + chip + 18, bottom - 12, 26, Ui.TEXT);
    }

    private void drawCard(Canvas c, long now) {
        AirData.Plane p = selected;
        float l = R - 470;
        float r = R - 16;
        float t = T + 14;
        float b = B - 56;
        Ui.card(c, l, t, r, b, 0xF0060A12);
        Ui.bold(c, p.name(), l + 22, t + 54, 42, p.emergency() ? Ui.RED : Ui.TEXT);
        float y = t + 90;
        if (p.emergency()) {
            Ui.bold(c, "SQUAWK " + p.squawk + " " + AirData.squawkMeaning(p.squawk).toUpperCase(Locale.ROOT), l + 22, y, 23, Ui.RED);
            y += 34;
        }
        if (!p.emergency() && !reportedSquawk.isEmpty() && !reportedSquawk.equals(p.squawk)) {
            String squawkNow = p.squawk.isEmpty() ? "none" : p.squawk;
            Ui.bold(c, "UNCONFIRMED " + reportedSquawk + ", NOW " + squawkNow, l + 22, y, 23, Ui.AMBER);
            y += 34;
        }
        if (p.military) {
            Ui.bold(c, "MILITARY", l + 22, y, 23, MILITARY);
            y += 34;
        }
        if (p.estimated) {
            Ui.bold(c, "ESTIMATED, LAST HEARD " + Parsers.ago(p.timeMs, now).toUpperCase(Locale.ROOT) + " AGO", l + 22, y, 23, Ui.AMBER);
            y += 34;
        }
        if (selectedLost) {
            Ui.bold(c, "SIGNAL LOST, LAST HEARD " + Parsers.ago(p.timeMs, now).toUpperCase(Locale.ROOT) + " AGO", l + 22, y, 23, Ui.AMBER);
            y += 34;
        }
        boolean settled = watchSettled(now);
        AirData.RouteSet set = settled ? feed.routes(p.callsign) : null;
        AirData.AircraftInfo info = settled ? feed.aircraft(p.hex) : null;
        AirData.RouteCheck check = checkedRoute(now);
        AirData.Route route = check == null ? null : check.route;
        String operator = set != null && !set.airline.isEmpty() ? set.airline : info != null ? info.owner : "";
        if (!operator.isEmpty()) {
            Ui.line(c, operator, l + 22, y, r - l - 44, 25, Ui.CYAN, Ui.SANS);
            y += 34;
        }
        if (info != null && !info.name.isEmpty()) {
            Ui.line(c, info.name, l + 22, y, r - l - 44, 22, Ui.DIM, Ui.SANS);
            y += 34;
        }
        if (!p.ground && p.altitudeFeet > 0 && p.altitudeFeet < 10_000 && AirData.airliner(p)) {
            Ui.mono(c, "BELOW 10,000 FT: LAPTOPS AWAY", l + 22, y, 20, Ui.AMBER);
            y += 32;
        }
        if (route != null) {
            Ui.mono(c, check.state == AirData.ROUTE_FLIPPED ? "LIKELY ROUTE, SWAPPED TO MATCH HEADING"
                    : check.state == AirData.ROUTE_UNSURE ? "ROUTE ON FILE, HEADING DOES NOT MATCH" : "LIKELY ROUTE, CHECKED AGAINST POSITION",
                    l + 22, y, 18, check.trusted() ? Ui.FAINT : Ui.AMBER);
            y += 30;
            Ui.line(c, "FROM " + route.from, l + 22, y, r - l - 44, 24, Ui.TEXT, Ui.SANS);
            y += 34;
            Ui.line(c, "TO   " + route.to, l + 22, y, r - l - 44, 24, Ui.TEXT, Ui.SANS);
            y += 42;
        } else if (check != null && !set.legs.isEmpty()) {
            Ui.mono(c, "ROUTE ON FILE DOES NOT FIT THIS FLIGHT", l + 22, y, 18, Ui.FAINT);
            y += 36;
        }
        if (route != null && check.trusted()) {
            AirData.Weather weather = feed.weather(route.toIcao, now);
            if (weather != null && !weather.line.isEmpty()) {
                String airport = route.to.indexOf(' ') > 0 ? route.to.substring(0, route.to.indexOf(' ')) : route.to;
                String line = airport + " NOW: " + (weather.category.isEmpty() ? "" : weather.category + ", ") + weather.line;
                Ui.line(c, line, l + 22, y, r - l - 44, 22, weatherColor(weather.category), Ui.SANS);
                y += 36;
            }
        }
        // Most wanted first: on a busy card the last ones are left out rather than running into the graph.
        float last = GRAPH_B - GRAPH_H - 8;
        if (route != null && route.toPoint != null && check.trusted()) {
            double toGoNm = AirData.nauticalMiles(AirData.lat(p.yAt(now)), AirData.lon(p.xAt(now)), route.toPoint[0], route.toPoint[1]);
            if (y < last) y = field(c, "TO GO", String.format(Locale.US, "%,.0f nm", toGoNm), l, y);
            int minutes = AirData.minutesToLand(toGoNm, p.speedKnots, AirData.cruiseKnots(p.kind));
            if (minutes >= 0 && y < last) y = field(c, "LANDS", "about " + AirData.duration(minutes) + ", " + clock(now + minutes * 60_000L), l, y);
        }
        // Until it settles, this could still be the path of the aircraft hopped from.
        AirData.Track track = settled ? feed.currentTrack : null;
        if (track != null && y < last) {
            String flown = AirData.duration(Math.max(0, now - track.startMs) / 60_000);
            y = field(c, track.sawTakeoff() ? "IN AIR" : "TRACKED", flown + (track.sawTakeoff() ? "" : "+")
                    + String.format(Locale.US, ", %,d nm", track.flownNm), l, y);
        }
        if (y < last) y = field(c, "ALTITUDE", p.ground ? "on the ground" : String.format(Locale.US, "%,d ft %s", p.altitudeFeet, climbWord(p.climbFpm)), l, y);
        if (p.targetFeet > 0 && Math.abs(p.targetFeet - p.altitudeFeet) > 300 && y < last) {
            y = field(c, "SET TO", String.format(Locale.US, "%,d ft", p.targetFeet), l, y);
        }
        if (y < last) y = field(c, "SPEED", p.speedKnots + " kt" + (p.mach > 0 ? String.format(Locale.US, "  mach %.2f", p.mach) : ""), l, y);
        if (y < last) y = field(c, "TYPE", join(p.type, p.registration), l, y);
        if (settled && track == null && liveTrack != null && y < last) {
            y = field(c, "SEEN FOR", AirData.duration(Math.max(0, now - liveTrack.startMs) / 60_000) + "+", l, y);
        }
        if (settled && track == null && !feed.trackState.isEmpty() && y < last) y = field(c, "PATH", feed.trackState, l, y);
        if (p.windKnots >= 0 && p.windFrom >= 0 && y < last) {
            y = field(c, "WIND", String.format(Locale.US, "%d kt from %03d", p.windKnots, p.windFrom), l, y);
        }
        if (y < last) y = field(c, "HEADING", String.format(Locale.US, "%03.0f deg", p.track), l, y);
        if (y < last) field(c, "POSITION", String.format(Locale.US, "%.2f, %.2f", AirData.lat(p.yAt(now)), AirData.lon(p.xAt(now))), l, y);
        String link = "https://globe.adsbexchange.com/?icao=" + p.hex;
        if (!link.equals(qrFor)) {
            qrFor = link;
            qr = Qr.encode(link);
        }
        float qrLeft = TILE_L + MODEL_W + 15;
        // A source can ask for its picture to link to its page. A TV has no links, so the code takes turns showing that.
        AirData.Photo shown = feed.photo == null ? null : feed.photoDetails;
        String photoLink = shown == null ? "" : shown.link;
        if (!photoLink.equals(photoQrFor)) {
            photoQrFor = photoLink;
            photoQr = photoLink.isEmpty() ? null : Qr.encode(photoLink);
        }
        boolean showPhotoPage = photoQr != null && (now / QR_SWAP_MS) % 2 == 1;
        boolean[][] code = showPhotoPage ? photoQr : qr;
        if (code != null) Ui.qr(c, code, qrLeft, TILE_T, QR_SIDE);
        Ui.centre(c, showPhotoPage ? "SCAN: PHOTO PAGE" : "SCAN TO TRACK", qrLeft + QR_SIDE / 2, TILE_B, 15, Ui.DIM);
    }

    /** The selected aircraft's class as a slowly turning solid: its faces are flat-shaded and painted back to front. */
    private void drawModel(Canvas c, long now) {
        int kind = selected.kind;
        if (kind == Aircraft.UNKNOWN) {
            Ui.card(c, TILE_L, TILE_T, TILE_L + MODEL_W, TILE_B, Ui.PANEL);
            Ui.centre(c, "NO PHOTO, TYPE UNKNOWN", TILE_L + MODEL_W / 2, (TILE_T + TILE_B) / 2, 15, Ui.FAINT);
            return;
        }
        if (kind != modelKind) {
            modelKind = kind;
            model = Aircraft.model(kind);
            turned = new float[model.vertices.length];
            depths = new float[model.faces.length / 4];
            order = new int[depths.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
        }
        float l = TILE_L;
        float r = l + MODEL_W;
        float b = TILE_B;
        float t = TILE_T;
        Ui.card(c, l, t, r, b, Ui.PANEL);
        Ui.mono(c, Aircraft.name(kind).toUpperCase(Locale.ROOT), l + 12, t + 24, 16, Ui.TEXT);
        Ui.mono(c, "CLASS SHAPE ONLY", l + 12, b - 10, 14, Ui.FAINT);

        double yaw = (now % 16_000) / 16_000.0 * 2 * Math.PI;
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float cosP = 0.92f;
        float sinP = 0.39f;
        float scale = MODEL_W * 0.62f;
        float cx = (l + r) / 2;
        float cy = (t + b) / 2 + 8;
        float[] v = model.vertices;
        for (int i = 0; i < v.length; i += 3) {
            float x = v[i] * cosY - v[i + 1] * sinY;
            float y = v[i] * sinY + v[i + 1] * cosY;
            turned[i] = x;
            turned[i + 1] = y * cosP + v[i + 2] * sinP;
            turned[i + 2] = y * sinP - v[i + 2] * cosP;
        }
        int[] faces = model.faces;
        for (int f = 0; f < depths.length; f++) {
            float sum = 0;
            for (int k = 0; k < 4; k++) sum += turned[faces[f * 4 + k] * 3 + 1];
            depths[f] = sum;
        }
        // Barely changes between frames, and allocates nothing.
        for (int i = 1; i < order.length; i++) {
            int value = order[i];
            int j = i - 1;
            while (j >= 0 && depths[order[j]] > depths[value]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = value;
        }
        int color = selected.military ? MILITARY : Ui.BLUE;
        for (int n = order.length - 1; n >= 0; n--) {
            int f = order[n] * 4;
            int a = faces[f] * 3;
            int e = faces[f + 1] * 3;
            int g = faces[f + 2] * 3;
            int h = faces[f + 3] * 3;
            modelPaint.setColor(shade(color, a, e, g, h));
            face.rewind();
            face.moveTo(cx + turned[a] * scale, cy + turned[a + 2] * scale);
            face.lineTo(cx + turned[e] * scale, cy + turned[e + 2] * scale);
            face.lineTo(cx + turned[g] * scale, cy + turned[g + 2] * scale);
            face.lineTo(cx + turned[h] * scale, cy + turned[h + 2] * scale);
            face.close();
            c.drawPath(face, modelPaint);
        }
    }

    /** Brightness from how squarely a face meets a light above the viewer, either side of it. */
    private int shade(int color, int a, int e, int g, int h) {
        float ux = turned[g] - turned[a];
        float uy = turned[g + 1] - turned[a + 1];
        float uz = turned[g + 2] - turned[a + 2];
        float vx = turned[h] - turned[e];
        float vy = turned[h + 1] - turned[e + 1];
        float vz = turned[h + 2] - turned[e + 2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float light = length < 1e-6f ? 0.5f : Math.abs(0.3f * nx + 0.5f * ny - 0.81f * nz) / length;
        float level = 0.28f + 0.72f * light;
        return 0xFF000000 | ((int) (((color >> 16) & 255) * level) << 16)
                | ((int) (((color >> 8) & 255) * level) << 8) | (int) ((color & 255) * level);
    }

    private static float field(Canvas c, String name, String value, float l, float y) {
        Ui.mono(c, name, l + 22, y, 20, Ui.FAINT);
        Ui.mono(c, value.isEmpty() ? "--" : value, l + 150, y, 23, Ui.TEXT);
        return y + 32;
    }

    /** The usual chart colours for flying conditions. */
    private static int weatherColor(String category) {
        switch (category) {
            case "VFR": return Ui.GREEN;
            case "MVFR": return Ui.BLUE;
            case "IFR": return Ui.RED;
            case "LIFR": return Ui.MAGENTA;
            default: return Ui.TEXT;
        }
    }

    /** The real aircraft, cropped to the tile, with the credit its photographer is owed. */
    private void drawPhoto(Canvas c, Bitmap bitmap) {
        float l = TILE_L;
        float r = l + MODEL_W;
        float b = TILE_B;
        float t = TILE_T;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int cropWidth = Math.min(width, (int) (height * MODEL_W / MODEL_H));
        int cropHeight = Math.min(height, (int) (width * MODEL_H / MODEL_W));
        photoCrop.set((width - cropWidth) / 2, (height - cropHeight) / 2, (width + cropWidth) / 2, (height + cropHeight) / 2);
        dest.set(l, t, r, b);
        c.drawBitmap(bitmap, photoCrop, dest, photoPaint);
        AirData.Photo details = feed.photoDetails;
        if (details == null) return;
        String by = details.photographer.isEmpty() ? "Photo" : "Photo: " + details.photographer;
        Ui.FILL.setColor(0xD0060A12);
        if (details.similarOnly()) {
            // Must not pass for the aircraft on the map, and its licence asks for the credit and the terms.
            c.drawRect(l, b - 60, r, b, Ui.FILL);
            Ui.mono(c, "SIMILAR, NOT THIS AIRCRAFT", l + 8, b - 43, 13, Ui.AMBER);
            Ui.line(c, by, l + 8, b - 24, MODEL_W - 16, 14, Ui.TEXT, Ui.SANS);
            Ui.line(c, details.licence + ", Wikimedia", l + 8, b - 7, MODEL_W - 16, 13, Ui.DIM, Ui.SANS);
        } else {
            c.drawRect(l, b - 42, r, b, Ui.FILL);
            Ui.line(c, by, l + 8, b - 24, MODEL_W - 16, 15, Ui.TEXT, Ui.SANS);
            Ui.mono(c, details.source, l + 8, b - 7, 14, Ui.DIM);
        }
    }

    /** Altitude as bars in the map's colours, with ground speed over it as a line. */
    private void drawProfile(Canvas c) {
        boolean live = feed.currentTrack == null;
        AirData.Track track = live ? liveTrack : feed.currentTrack;
        float l = TILE_L;
        float r = l + GRAPH_W;
        float b = GRAPH_B;
        float t = b - GRAPH_H;
        Ui.card(c, l, t, r, b, Ui.PANEL);
        if (track == null || !watchSettled(System.currentTimeMillis())) {
            Ui.centre(c, "GRAPH BUILDS AS IT FLIES", (l + r) / 2, (t + b) / 2 + 5, 15, Ui.FAINT);
            return;
        }
        if (track != profileFor) {
            profileFor = track;
            profile = AirData.profile(track, GRAPH_SLICES);
        }
        Ui.mono(c, String.format(Locale.US, "ALT %,d FT", profile.maxFeet), l + 12, t + 20, 15, Ui.DIM);
        Ui.right(c, "LINE " + profile.maxKnots + " KT", r - 12, t + 20, 15, Ui.TEXT);
        if (live) Ui.centre(c, "SEEN LIVE", (l + r) / 2, t + 20, 13, Ui.FAINT);
        float left = l + 12;
        float bottom = b - 10;
        float height = GRAPH_H - 36;
        float slice = (GRAPH_W - 24) / GRAPH_SLICES;
        float feetScale = height / Math.max(1, profile.maxFeet);
        float speedScale = height / Math.max(1, profile.maxKnots);
        for (int i = 0; i < GRAPH_SLICES; i++) {
            int feet = Math.max(0, profile.feet[i]);
            Ui.FILL.setColor(altitudeColor(feet));
            c.drawRect(left + i * slice, bottom - feet * feetScale, left + (i + 1) * slice - 1, bottom, Ui.FILL);
        }
        for (int i = 0; i + 1 < GRAPH_SLICES; i++) {
            speedLine[i * 4] = left + (i + 0.5f) * slice;
            speedLine[i * 4 + 1] = bottom - profile.knots[i] * speedScale;
            speedLine[i * 4 + 2] = left + (i + 1.5f) * slice;
            speedLine[i * 4 + 3] = bottom - profile.knots[i + 1] * speedScale;
        }
        Ui.STROKE.setColor(Ui.TEXT);
        Ui.STROKE.setStrokeWidth(2);
        c.drawLines(speedLine, Ui.STROKE);
    }

    /** A time of day, in the 24-hour form the deck's own clock uses. */
    private static String clock(long ms) {
        java.util.Calendar at = java.util.Calendar.getInstance();
        at.setTimeInMillis(ms);
        return String.format(Locale.US, "%02d:%02d", at.get(java.util.Calendar.HOUR_OF_DAY), at.get(java.util.Calendar.MINUTE));
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        return b.isEmpty() ? a : a + "  " + b;
    }

    private static String climbWord(int fpm) {
        if (fpm > 300) return "climbing";
        return fpm < -300 ? "descending" : "";
    }

    private void drawControls(Canvas c) {
        float w = 760;
        float h = 400;
        float l = MX - w / 2;
        float t = MY - h / 2;
        Ui.card(c, l, t, l + w, t + h, 0xF4060A12);
        Ui.STROKE.setColor(Ui.BLUE);
        Ui.STROKE.setStrokeWidth(3);
        dest.set(l, t, l + w, t + h);
        c.drawRoundRect(dest, 10, 10, Ui.STROKE);
        if (moreMenu) {
            Ui.centre(c, "MORE", MX, t + 52, 30, Ui.BLUE);
            Ui.centre(c, "UP", MX, t + 120, 30, Ui.TEXT);
            Ui.centre(c, "LIVE AIRPORT CAMS", MX, t + 152, 22, Ui.BLUE);
            Ui.centre(c, "LEFT", l + 170, t + 190, 30, Ui.TEXT);
            Ui.centre(c, "HOME", l + 170, t + 222, 22, Ui.DIM);
            Ui.centre(c, "RIGHT", l + w - 200, t + 190, 30, Ui.TEXT);
            Ui.centre(c, militaryOnly ? "SHOW ALL AIRCRAFT" : "MILITARY ONLY", l + w - 200, t + 222, 22, MILITARY);
            Ui.centre(c, "OK OR BACK RETURNS", MX, t + 382, 20, Ui.FAINT);
            return;
        }
        Ui.centre(c, "MAP CONTROLS", MX, t + 52, 30, Ui.BLUE);
        Ui.centre(c, "UP", MX, t + 120, 30, Ui.TEXT);
        Ui.centre(c, "ZOOM IN", MX, t + 152, 22, Ui.DIM);
        Ui.centre(c, "LEFT", l + 120, t + 210, 30, Ui.TEXT);
        Ui.centre(c, "HOME, FILTER, CAMS", l + 130, t + 242, 22, Ui.DIM);
        Ui.centre(c, "RIGHT", l + w - 130, t + 210, 30, Ui.TEXT);
        boolean anyEmergency = emergencies > 0;
        boolean anyMilitary = false;
        for (int i = 0; i < feed.planes.size() && !anyMilitary; i++) anyMilitary = feed.planes.get(i).military;
        Ui.centre(c, anyEmergency ? "NEXT EMERGENCY" : anyMilitary ? "NEXT MILITARY" : "NEAREST AIRCRAFT", l + w - 130, t + 242, 22,
                anyEmergency ? Ui.RED : anyMilitary ? MILITARY : Ui.DIM);
        Ui.centre(c, "OK", MX, t + 210, 30, Ui.BLUE);
        String middle = selected != null ? "CLOSE MENU"
                : AirData.pickStart(feed.planes, pickView(), System.currentTimeMillis()) != null ? "PICK AIRCRAFT" : "ZOOM IN TO PICK";
        Ui.centre(c, middle, MX, t + 242, 22, Ui.BLUE);
        Ui.centre(c, "DOWN", MX, t + 300, 30, Ui.TEXT);
        Ui.centre(c, "ZOOM OUT", MX, t + 332, 22, Ui.DIM);
        Ui.centre(c, "BACK CLOSES", MX, t + 382, 20, Ui.FAINT);
    }
}
