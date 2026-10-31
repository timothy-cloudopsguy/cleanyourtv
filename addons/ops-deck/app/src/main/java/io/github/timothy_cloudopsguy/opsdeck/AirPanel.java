package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A live traffic map on the D-pad. Arrows move the map, OK opens the controls (zoom, home, next aircraft), and the
 * channel or fast-forward keys zoom directly. Keys stay here until Back, since Left has to move the map.
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
    private static final int[] ALT_FEET = {5_000, 15_000, 30_000, 45_000};
    private static final int[] ALT_COLOR = {Ui.AMBER, Ui.GREEN, Ui.CYAN, Ui.BLUE, Ui.MAGENTA};
    private static final String[] ALT_NAME = {"<5k", "<15k", "<30k", "<45k", "45k+"};

    private final AirFeed feed;
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dest = new RectF();
    private final Path glyph = new Path();
    private final Path trailPath = new Path();
    private final float[][] dots = new float[ALT_COLOR.length + 1][];
    private final int[] dotCount = new int[ALT_COLOR.length + 1];
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
    private AirData.Plane selected;
    private int selectedVersion = -1;
    private boolean follow;
    private String qrFor = "";
    private boolean[][] qr;
    private int inView;
    private int emergencies;
    private String emergencyName = "";
    private double[][] toGo;
    private String toGoFor = "";
    /** The emergency code that led here, kept so a feed that disagrees is shown rather than silently overruling it. */
    private String reportedSquawk = "";
    private int toGoVersion = -1;

    AirPanel(Deck deck, Context context) {
        super(deck);
        feed = new AirFeed(context, deck.prefs, context.getString(io.github.timothy_cloudopsguy.opsdeck.R.string.home_lat),
                context.getString(io.github.timothy_cloudopsguy.opsdeck.R.string.home_lon));
        for (int i = 0; i < dots.length; i++) dots[i] = new float[4096];
        tilePaint.setColorFilter(new ColorMatrixColorFilter(darkMap()));
        glyph.moveTo(0, -15);
        glyph.lineTo(11, 12);
        glyph.lineTo(0, 6);
        glyph.lineTo(-11, 12);
        glyph.close();
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
        if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            zoomBy(1);
            return true;
        }
        if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
            zoomBy(-1);
            return true;
        }
        if (controls) return controlKey(key);
        if (Ui.isOk(key)) {
            controls = true;
            deck.sfx.tick();
            return true;
        }
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

    private boolean controlKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                zoomBy(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                zoomBy(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                goHome();
                controls = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                selectNext();
                controls = false;
                return true;
            default:
                if (Ui.isOk(key)) {
                    controls = false;
                    deck.sfx.tick();
                    return true;
                }
                return false;
        }
    }

    @Override
    boolean back() {
        if (controls) {
            controls = false;
            return true;
        }
        if (selected != null) {
            selected = null;
            follow = false;
            trail.clear();
            return true;
        }
        return false;
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
        follow = false;
        deck.sfx.confirm();
    }

    /** Emergencies first, then whatever is nearest: from anywhere, this is the way to the one the banner names. */
    private void selectNext() {
        long now = System.currentTimeMillis();
        String current = selected == null ? "" : selected.hex;
        AirData.Plane next = AirData.nextEmergency(alarms, current);
        if (next == null) next = AirData.nearest(feed.planes, centerX, centerY, now, current);
        if (next == null) {
            deck.sfx.tick();
            return;
        }
        selected = next;
        reportedSquawk = next.emergency() ? next.squawk : "";
        selectedVersion = feed.version;
        follow = true;
        // Close enough for the live feed, which has the type, registration and frequent updates.
        zoom = Math.max(zoom, AirFeed.REGIONAL_MIN_ZOOM);
        toGoFor = "";
        trail.clear();
        trail.add(new double[]{next.xAt(now), next.yAt(now)});
        deck.sfx.confirm();
    }

    @Override
    void draw(Canvas c, long now) {
        settleHome();
        trackSelected(now);
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
        if (selected != null) drawCard(c, now);
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
                trail.add(new double[]{fresh.worldX, fresh.worldY});
                if (trail.size() > TRAIL_MAX) trail.remove(0);
            }
        }
        if (follow) {
            centerX = AirData.wrapX(selected.xAt(now));
            centerY = AirData.clampY(selected.yAt(now));
        }
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
        if (selected == null) return;
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
        if (selected == null) return;
        AirData.Route route = feed.route(selected.callsign);
        if (route == null) return;
        if (route.toPoint != null) {
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
        alarms.clear();
        for (int i = 0; i < dotCount.length; i++) dotCount[i] = 0;
        // First pass decides dots or arrows, and doubles as the count the HUD shows.
        for (int i = 0; i < planes.size(); i++) {
            AirData.Plane p = planes.get(i);
            // Counted wherever it is: the banner should point to one off screen too.
            if (p.emergency()) alarms.add(p);
            float x = screenX(p.xAt(now), world);
            float y = screenY(p.yAt(now), world);
            if (x < L || x > R || y < T || y > B) continue;
            visible++;
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
        emergencies = alarms.size();
        emergencyName = alarms.isEmpty() ? "" : alarms.get(0).name() + " " + alarms.get(0).squawk;
        if (visible > GLYPH_LIMIT) {
            drawDots(c);
        } else {
            for (int i = 0; i < planes.size(); i++) drawGlyph(c, planes.get(i), world, now, visible, selected != null);
        }
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
        int color = p.emergency() ? Ui.RED : bucketColor(bucket(p));
        Ui.FILL.setColor(dim ? dimmed(color) : color);
        c.drawPath(glyph, Ui.FILL);
        c.restoreToCount(saved);
        if (!dim && visible > 0 && zoom >= LABEL_MIN_ZOOM && visible <= LABEL_LIMIT && !p.ground) {
            Ui.mono(c, p.name(), x + 20, y + 7, 19, Ui.TEXT);
        }
    }

    private static int bucket(AirData.Plane p) {
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
        return bucket < ALT_COLOR.length ? ALT_COLOR[bucket] : Ui.FAINT;
    }

    private void drawCrosshair(Canvas c) {
        if (follow) return;
        Ui.FILL.setColor(0xB0E8F1FA);
        c.drawRect(MX - 16, MY - 1, MX - 5, MY + 1, Ui.FILL);
        c.drawRect(MX + 5, MY - 1, MX + 16, MY + 1, Ui.FILL);
        c.drawRect(MX - 1, MY - 16, MX + 1, MY - 5, Ui.FILL);
        c.drawRect(MX - 1, MY + 5, MX + 1, MY + 16, Ui.FILL);
    }

    private void drawHud(Canvas c, long now) {
        Ui.card(c, L + 14, T + 14, L + 590, T + 110, 0xE0060A12);
        Ui.bold(c, Ui.number(inView) + " IN VIEW", L + 32, T + 58, 34, Ui.TEXT);
        String from = feed.source.isEmpty() ? (feed.locating ? "Finding this TV..." : "Pulling traffic...")
                : feed.source + ", " + Parsers.ago(feed.updated, now) + " ago";
        Ui.mono(c, "z" + zoom + "  " + from, L + 32, T + 94, 21, Ui.DIM);
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
        Ui.card(c, L + 14, y, L + 1120, B - 12, 0xE0060A12);
        float x = L + 30;
        for (int i = 0; i < ALT_NAME.length; i++) {
            Ui.FILL.setColor(ALT_COLOR[i]);
            c.drawCircle(x + 7, y + 22, 7, Ui.FILL);
            Ui.mono(c, ALT_NAME[i], x + 20, y + 30, 19, Ui.DIM);
            x += 92;
        }
        Ui.mono(c, focused ? "ARROWS MOVE  OK CONTROLS  BACK EXIT" : "RIGHT OR OK TO FLY THE MAP", x + 10, y + 30, 21, Ui.TEXT);
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
            Ui.bold(c, "MILITARY", l + 22, y, 23, Ui.AMBER);
            y += 34;
        }
        AirData.Route route = feed.route(p.callsign);
        if (route != null) {
            if (!route.airline.isEmpty()) {
                Ui.line(c, route.airline, l + 22, y, r - l - 44, 25, Ui.CYAN, Ui.SANS);
                y += 36;
            }
            Ui.mono(c, "LIKELY ROUTE, BY FLIGHT NUMBER", l + 22, y, 18, Ui.FAINT);
            y += 30;
            Ui.line(c, "FROM " + route.from, l + 22, y, r - l - 44, 24, Ui.TEXT, Ui.SANS);
            y += 34;
            Ui.line(c, "TO   " + route.to, l + 22, y, r - l - 44, 24, Ui.TEXT, Ui.SANS);
            y += 42;
        }
        if (route != null && route.toPoint != null) {
            y = field(c, "TO GO", String.format(Locale.US, "%,.0f nm", AirData.nauticalMiles(AirData.lat(p.yAt(now)),
                    AirData.lon(p.xAt(now)), route.toPoint[0], route.toPoint[1])), l, y);
        }
        AirData.Track track = feed.currentTrack;
        y = field(c, "FLOWN", track != null ? String.format(Locale.US, "%,d nm seen", track.flownNm) : feed.trackState, l, y);
        y = field(c, "TYPE", join(p.type, p.registration), l, y);
        y = field(c, "ALTITUDE", p.ground ? "on the ground" : String.format(Locale.US, "%,d ft %s", p.altitudeFeet, climbWord(p.climbFpm)), l, y);
        y = field(c, "SPEED", p.speedKnots + " kt", l, y);
        y = field(c, "HEADING", String.format(Locale.US, "%03.0f deg", p.track), l, y);
        field(c, "POSITION", String.format(Locale.US, "%.2f, %.2f", AirData.lat(p.yAt(now)), AirData.lon(p.xAt(now))), l, y);
        String link = "https://globe.adsbexchange.com/?icao=" + p.hex;
        if (!link.equals(qrFor)) {
            qrFor = link;
            qr = Qr.encode(link);
        }
        if (qr != null) Ui.qr(c, qr, l + 22, b - 182, 164);
        Ui.mono(c, "SCAN TO TRACK", l + 200, b - 118, 20, Ui.TEXT);
        Ui.mono(c, follow ? "Following" : "Arrows left it", l + 200, b - 88, 20, Ui.DIM);
        Ui.mono(c, "Back deselects", l + 200, b - 58, 20, Ui.DIM);
    }

    private static float field(Canvas c, String name, String value, float l, float y) {
        Ui.mono(c, name, l + 22, y, 20, Ui.FAINT);
        Ui.mono(c, value.isEmpty() ? "--" : value, l + 150, y, 23, Ui.TEXT);
        return y + 36;
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
        float w = 620;
        float h = 400;
        float l = MX - w / 2;
        float t = MY - h / 2;
        Ui.card(c, l, t, l + w, t + h, 0xF4060A12);
        Ui.STROKE.setColor(Ui.BLUE);
        Ui.STROKE.setStrokeWidth(3);
        dest.set(l, t, l + w, t + h);
        c.drawRoundRect(dest, 10, 10, Ui.STROKE);
        Ui.centre(c, "MAP CONTROLS", MX, t + 52, 30, Ui.BLUE);
        Ui.centre(c, "UP", MX, t + 120, 30, Ui.TEXT);
        Ui.centre(c, "ZOOM IN", MX, t + 152, 22, Ui.DIM);
        Ui.centre(c, "LEFT", l + 120, t + 210, 30, Ui.TEXT);
        Ui.centre(c, "HOME", l + 120, t + 242, 22, Ui.DIM);
        Ui.centre(c, "RIGHT", l + w - 130, t + 210, 30, Ui.TEXT);
        boolean anyEmergency = emergencies > 0;
        Ui.centre(c, anyEmergency ? "NEXT EMERGENCY" : "NEXT AIRCRAFT", l + w - 130, t + 242, 22, anyEmergency ? Ui.RED : Ui.DIM);
        Ui.centre(c, "DOWN", MX, t + 300, 30, Ui.TEXT);
        Ui.centre(c, "ZOOM OUT", MX, t + 332, 22, Ui.DIM);
        Ui.centre(c, "OK CLOSES", MX, t + 382, 20, Ui.FAINT);
    }
}
