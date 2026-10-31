package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The air map's network side: aircraft, map tiles, routes and the first guess at where the TV is. Nothing here runs by
 * itself: AirPanel calls in while it draws, so the fetching stops whenever the map is not on screen.
 */
final class AirFeed {
    /** A 250 nautical mile circle covers the screen from here in. */
    static final int REGIONAL_MIN_ZOOM = 7;
    private static final int RADIUS_NM = 250;
    private static final long REGIONAL_MS = 5_000;
    private static final long REGIONAL_MOVED_MS = 1_500;
    private static final double REFETCH_MOVED_NM = 90;
    // Anonymous OpenSky allows 400 credits a day and a world snapshot costs 4.
    private static final long WORLD_MS = 120_000;
    private static final long WORLD_LIMITED_MS = 30 * 60_000;
    private static final long TILE_RETRY_MS = 60_000;
    private static final long LOCATION_MAX_AGE_MS = 30L * 24 * 3600_000;
    private static final int MAX_TILES_IN_MEMORY = 48;
    private static final int MAX_TILES_ON_DISK = 800;
    private static final int MAX_QUEUED_TILES = 40;

    private static final String REGIONAL_LOL = "https://api.adsb.lol/v2/lat/%.3f/lon/%.3f/dist/%d";
    private static final String REGIONAL_FI = "https://opendata.adsb.fi/api/v2/lat/%.3f/lon/%.3f/dist/%d";
    private static final String WORLD = "https://opensky-network.org/api/states/all";
    private static final String TRACK = "https://opensky-network.org/api/tracks/all?time=0&icao24=";
    private static final long TRACK_REFRESH_MS = 5 * 60_000;
    private static final String ROUTE = "https://api.adsbdb.com/v0/callsign/";
    private static final String LOCATE = "https://ipwho.is/";
    private static final String TILE = "https://tile.openstreetmap.org/%d/%d/%d.png";

    volatile List<AirData.Plane> planes = Collections.emptyList();
    volatile int version;
    volatile String source = "";
    volatile String problem = "";
    volatile long updated;
    /** Set once: {lat, lon}. */
    volatile double[] home;
    volatile boolean locating = true;
    volatile AirData.Track currentTrack;
    volatile String trackState = "";
    private volatile String trackHex = "";
    private long trackAsked;

    private final File dir;
    private final File tileDir;
    private final SharedPreferences prefs;
    private final ExecutorService planeThread = Executors.newSingleThreadExecutor();
    private final ExecutorService tileThreads = Executors.newFixedThreadPool(2);
    private final Map<String, AirData.Route> routes = Collections.synchronizedMap(new HashMap<>());
    private final Set<String> routesAsked = Collections.synchronizedSet(new HashSet<>());
    private final LruCache<String, Bitmap> tiles = new LruCache<>(MAX_TILES_IN_MEMORY);
    private final Map<String, Long> tileFailed = Collections.synchronizedMap(new HashMap<>());
    private final ArrayDeque<int[]> tileQueue = new ArrayDeque<>();
    private final Set<String> tileWanted = new HashSet<>();

    private boolean started;
    private volatile boolean busy;
    private volatile boolean wantRegional = true;
    private long lastRegional;
    private double lastLat;
    private double lastLon;
    private long worldLimitedUntil;

    AirFeed(Context context, SharedPreferences prefs, String homeLat, String homeLon) {
        dir = context.getCacheDir();
        tileDir = new File(dir, "osm-tiles");
        tileDir.mkdirs();
        this.prefs = prefs;
        double[] configured = configured(homeLat, homeLon);
        if (configured != null) {
            home = configured;
            locating = false;
            started = true;
        }
    }

    private static double[] configured(String lat, String lon) {
        try {
            return lat.isEmpty() || lon.isEmpty() ? null : new double[]{Double.parseDouble(lat), Double.parseDouble(lon)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Called each frame: asks for fresh aircraft when they are due for what the screen shows. */
    void poll(double lat, double lon, int zoom, long now) {
        locateOnce(now);
        wantRegional = zoom >= REGIONAL_MIN_ZOOM;
        if (busy) return;
        if (wantRegional) {
            boolean moved = AirData.nauticalMiles(lat, lon, lastLat, lastLon) > REFETCH_MOVED_NM;
            long wait = moved ? REGIONAL_MOVED_MS : REGIONAL_MS;
            if (now - lastRegional < wait) return;
            lastRegional = now;
            lastLat = lat;
            lastLon = lon;
            busy = true;
            planeThread.execute(() -> fetchRegional(lat, lon));
        } else {
            if (now < worldLimitedUntil) {
                problem = "World feed's daily limit reached: zoom in for live traffic";
                return;
            }
            File file = new File(dir, "air-world.feed");
            boolean showingWorld = source.startsWith("OpenSky");
            if (showingWorld && now - file.lastModified() < WORLD_MS) return;
            busy = true;
            planeThread.execute(() -> fetchWorld(file));
        }
    }

    private void fetchRegional(double lat, double lon) {
        try {
            File file = new File(dir, "air-regional.feed");
            String name = "adsb.lol";
            try {
                Store.download(String.format(Locale.US, REGIONAL_LOL, lat, lon, RADIUS_NM), file);
            } catch (IOException first) {
                name = "adsb.fi";
                Store.download(String.format(Locale.US, REGIONAL_FI, lat, lon, RADIUS_NM), file);
            }
            List<AirData.Plane> parsed = AirData.parseRegional(Store.read(file), System.currentTimeMillis());
            if (wantRegional) publish(parsed, name);
        } catch (Exception e) {
            if (wantRegional) problem = "Live traffic is not answering";
        } finally {
            busy = false;
        }
    }

    private void fetchWorld(File file) {
        long now = System.currentTimeMillis();
        try {
            // A reopen within the same two minutes reuses the snapshot rather than spending credits on it.
            if (now - file.lastModified() >= WORLD_MS) Store.download(WORLD, file);
            List<AirData.Plane> parsed = AirData.parseOpenSky(Store.read(file), now);
            if (!wantRegional) publish(parsed, "OpenSky world");
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("429")) worldLimitedUntil = now + WORLD_LIMITED_MS;
            else worldLimitedUntil = now + WORLD_MS / 2;
            if (!wantRegional) problem = "World feed is not answering: zoom in for live traffic";
        } catch (Exception e) {
            worldLimitedUntil = now + WORLD_MS / 2;
            if (!wantRegional) problem = "World feed sent something unreadable";
        } finally {
            busy = false;
        }
    }

    private void publish(List<AirData.Plane> parsed, String name) {
        planes = parsed;
        source = name;
        problem = "";
        updated = System.currentTimeMillis();
        version++;
    }

    /** The flown path of the aircraft selected, if known yet; asks when it changes and every few minutes after. */
    AirData.Track track(String hex, long now) {
        if (hex.equals(trackHex) && now - trackAsked < TRACK_REFRESH_MS) return currentTrack;
        boolean changed = !hex.equals(trackHex);
        trackHex = hex;
        trackAsked = now;
        if (changed) {
            currentTrack = null;
            trackState = "Finding flight path...";
        }
        planeThread.execute(() -> fetchTrack(hex));
        return currentTrack;
    }

    private void fetchTrack(String hex) {
        String state;
        AirData.Track found = null;
        try {
            File file = new File(dir, "air-track.feed");
            Store.download(TRACK + hex.toLowerCase(Locale.ROOT), file);
            found = AirData.parseTrack(Store.read(file), System.currentTimeMillis());
            state = found == null ? "No flight path on record" : "";
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            state = message.contains("429") ? "Path limit reached for today"
                    : message.contains("404") ? "No flight path on record" : "Flight path not answering";
        } catch (Exception e) {
            state = "No flight path on record";
        }
        if (!hex.equals(trackHex)) return;
        if (found != null || currentTrack == null) currentTrack = found;
        trackState = found != null || currentTrack == null ? state : "";
    }

    /** The route for a callsign, if known yet; the first ask starts the lookup. */
    AirData.Route route(String callsign) {
        if (callsign.isEmpty()) return null;
        AirData.Route known = routes.get(callsign);
        if (known != null || !routesAsked.add(callsign)) return known;
        planeThread.execute(() -> {
            try {
                File file = new File(dir, "air-route.feed");
                Store.download(ROUTE + callsign, file);
                AirData.Route route = AirData.parseRoute(Store.read(file));
                if (route != null) routes.put(callsign, route);
            } catch (Exception e) {
                // adsbdb answers 404 for a flight it does not know: the card just shows no route.
            }
        });
        return null;
    }

    /** Where the TV is, roughly, from its internet address; asked once and remembered for a month. */
    private void locateOnce(long now) {
        if (started) return;
        started = true;
        long at = prefs.getLong("home_at", 0);
        if (at > 0 && now - at < LOCATION_MAX_AGE_MS) {
            home = new double[]{prefs.getFloat("home_lat", 0), prefs.getFloat("home_lon", 0)};
            locating = false;
            return;
        }
        planeThread.execute(() -> {
            try {
                File file = new File(dir, "air-where.feed");
                Store.download(LOCATE, file);
                double[] found = AirData.parseLocation(Store.read(file));
                // The answer also holds the address it was worked out from: only the two numbers are kept.
                file.delete();
                if (found != null) {
                    prefs.edit().putLong("home_at", System.currentTimeMillis())
                            .putFloat("home_lat", (float) found[0]).putFloat("home_lon", (float) found[1]).apply();
                    home = found;
                }
            } catch (Exception e) {
                // Stay on the wide view: the map still works, and the owner can set home_lat and home_lon.
            } finally {
                locating = false;
            }
        });
    }

    /** A tile if it is ready; otherwise null, and it is fetched in the background. Call for what is on screen now. */
    Bitmap tile(int z, int x, int y, long now) {
        String key = z + "/" + x + "/" + y;
        Bitmap ready = tiles.get(key);
        if (ready != null) return ready;
        Long failed = tileFailed.get(key);
        if (failed != null && now - failed < TILE_RETRY_MS) return null;
        synchronized (tileQueue) {
            if (tileWanted.add(key)) {
                tileQueue.addLast(new int[]{z, x, y});
                while (tileQueue.size() > MAX_QUEUED_TILES) {
                    int[] old = tileQueue.pollFirst();
                    tileWanted.remove(old[0] + "/" + old[1] + "/" + old[2]);
                }
                tileThreads.execute(this::loadNextTile);
            }
        }
        return null;
    }

    /** Newest request first: after a fast pan, what is on screen now beats what was a second ago. */
    private void loadNextTile() {
        int[] t;
        String key;
        synchronized (tileQueue) {
            t = tileQueue.pollLast();
            if (t == null) return;
            key = t[0] + "/" + t[1] + "/" + t[2];
        }
        try {
            File file = new File(tileDir, t[0] + "-" + t[1] + "-" + t[2] + ".png");
            if (!file.isFile()) {
                Store.download(String.format(Locale.US, TILE, t[0], t[1], t[2]), file);
                pruneTiles();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap != null) {
                tiles.put(key, bitmap);
            } else {
                file.delete();
                tileFailed.put(key, System.currentTimeMillis());
            }
        } catch (IOException e) {
            tileFailed.put(key, System.currentTimeMillis());
        } finally {
            synchronized (tileQueue) {
                tileWanted.remove(key);
            }
        }
    }

    private void pruneTiles() {
        String[] names = tileDir.list();
        if (names == null || names.length <= MAX_TILES_ON_DISK) return;
        File[] files = tileDir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_TILES_ON_DISK / 2; i++) files[i].delete();
    }
}
