package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private static final String MILITARY_FI = "https://opendata.adsb.fi/api/v2/mil";
    private static final String MILITARY_LOL = "https://api.adsb.lol/v2/mil";
    private static final long MILITARY_MS = 60_000;
    private static final long MILITARY_RETRY_MS = 3 * 60_000;
    private static final String WORLD = "https://opensky-network.org/api/states/all";
    private static final String TRACK = "https://opensky-network.org/api/tracks/all?time=0&icao24=";
    private static final long TRACK_REFRESH_MS = 5 * 60_000;
    /** OpenSky's path allowance is separate from its traffic one, and takes hours to come back. */
    private static final long TRACK_LIMITED_MS = 30 * 60_000;
    private static final String ROUTE = "https://api.adsbdb.com/v0/callsign/";
    private static final String ROUTE_VRS = "https://vrs-standing-data.adsb.lol/routes/%s/%s.json";
    private static final String AIRCRAFT = "https://api.adsbdb.com/v0/aircraft/";
    private static final String PHOTO = "https://airport-data.com/api/ac_thumb.json?n=1&m=";
    private static final String HEXDB = "https://hexdb.io/api/v1/aircraft/";
    private static final String PLANESPOTTERS_HEX = "https://api.planespotters.net/pub/photos/hex/";
    private static final String PLANESPOTTERS_REG = "https://api.planespotters.net/pub/photos/reg/";
    private static final String COMMONS_START = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=12&gsrsearch=";
    private static final String COMMONS_END = "&prop=imageinfo&iiprop=url|size|extmetadata&iiurlwidth=440&iiextmetadatafilter=LicenseShortName|Artist";
    private static final int PHOTO_WIDTH = 440;
    private static final String WEATHER = "https://aviationweather.gov/api/data/metar?format=json&ids=";
    private static final long WEATHER_REFRESH_MS = 15 * 60_000;
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
    private volatile long trackLimitedUntil;

    private final AirData.Estimates estimates;
    private volatile List<AirData.Plane> military = Collections.emptyList();
    private volatile boolean militaryBusy;
    private volatile long militaryDue;
    private final File dir;
    private final File tileDir;
    private final SharedPreferences prefs;
    private final ExecutorService planeThread = Executors.newSingleThreadExecutor();
    // Its own thread, so a slow photo site never holds up the aircraft.
    private final ExecutorService photoThread = Executors.newSingleThreadExecutor();
    private final Map<String, AirData.Weather> weather = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Long> weatherAsked = Collections.synchronizedMap(new HashMap<>());
    private volatile String photoHex = "";
    /** The picture of the aircraft in photoHex; null while loading or when there is none. Only one is ever kept. */
    volatile Bitmap photo;
    /** Who to credit for photo. */
    volatile AirData.Photo photoDetails;
    private final Map<String, AirData.Photo> similarPhotos = Collections.synchronizedMap(new HashMap<>());
    final AirData.History history = new AirData.History();
    private final ExecutorService tileThreads = Executors.newFixedThreadPool(2);
    private final Map<String, AirData.RouteSet> routes = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, AirData.AircraftInfo> aircraft = Collections.synchronizedMap(new HashMap<>());
    private final Set<String> aircraftAsked = Collections.synchronizedSet(new HashSet<>());
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

    AirFeed(Context context, SharedPreferences prefs, AirData.Estimates estimates, String homeLat, String homeLon) {
        this.estimates = estimates;
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
        if (now >= militaryDue && !militaryBusy) {
            militaryBusy = true;
            planeThread.execute(this::fetchMilitary);
        }
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

    /** Every military aircraft the network hears, worldwide, at every zoom. adsb.lol already gets the most from this app, so adsb.fi goes first. */
    private void fetchMilitary() {
        long now = System.currentTimeMillis();
        try {
            File file = new File(dir, "air-military.feed");
            try {
                Store.download(MILITARY_FI, file);
            } catch (IOException first) {
                Store.download(MILITARY_LOL, file);
            }
            military = AirData.parseRegional(Store.read(file), now);
            militaryDue = now + MILITARY_MS;
        } catch (Exception e) {
            militaryDue = now + MILITARY_RETRY_MS;
        } finally {
            militaryBusy = false;
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
            history.add(parsed, System.currentTimeMillis());
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
            estimates.update(parsed, now);
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
        planes = AirData.merge(parsed, military, estimates.current);
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
        if (now < trackLimitedUntil) {
            if (currentTrack == null) trackState = "Path limit reached";
            return currentTrack;
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
            state = found == null ? "No path on record" : "";
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("429")) trackLimitedUntil = System.currentTimeMillis() + TRACK_LIMITED_MS;
            // These share a card row about twenty characters wide.
            state = message.contains("429") ? "Path limit reached"
                    : message.contains("404") ? "No path on record" : "Path not answering";
        } catch (Exception e) {
            state = "No path on record";
        }
        if (!hex.equals(trackHex)) return;
        if (found != null || currentTrack == null) currentTrack = found;
        trackState = found != null || currentTrack == null ? state : "";
    }

    /**
     * The routes on file for a callsign, if known yet; the first ask starts the lookup. VRS was right more often when
     * tried against live traffic, so it comes first, but adsbdb fills gaps and is the one with the airline's name.
     */
    AirData.RouteSet routes(String callsign) {
        if (callsign.length() < 3) return null;
        AirData.RouteSet known = routes.get(callsign);
        if (known != null || !routesAsked.add(callsign)) return known;
        planeThread.execute(() -> {
            File file = new File(dir, "air-route.feed");
            List<AirData.Route> legs = new ArrayList<>();
            String airline = "";
            try {
                Store.download(String.format(Locale.US, ROUTE_VRS, callsign.substring(0, 2), callsign), file);
                legs.addAll(AirData.parseVrsRoute(Store.read(file)));
            } catch (Exception e) {
                // A flight VRS does not list answers 404.
            }
            try {
                Store.download(ROUTE + callsign, file);
                AirData.Route route = AirData.parseRoute(Store.read(file));
                if (route != null) {
                    legs.add(route);
                    airline = route.airline;
                }
            } catch (Exception e) {
                // adsbdb answers 404 for a flight it does not know.
            }
            routes.put(callsign, new AirData.RouteSet(airline, legs));
        });
        return null;
    }

    /** The latest weather at an airport, if known yet, asked for now and then while it is wanted. */
    AirData.Weather weather(String icao, long now) {
        if (icao.isEmpty()) return null;
        Long asked = weatherAsked.get(icao);
        if (asked == null || now - asked >= WEATHER_REFRESH_MS) {
            weatherAsked.put(icao, now);
            planeThread.execute(() -> {
                try {
                    File file = new File(dir, "air-weather.feed");
                    Store.download(WEATHER + icao, file);
                    AirData.Weather found = AirData.parseWeather(Store.read(file));
                    if (found != null) weather.put(icao, found);
                } catch (Exception e) {
                    // The card just leaves the line out.
                }
            });
        }
        return weather.get(icao);
    }

    /** Asks for a picture of this aircraft, once each time it changes. If none can be found, photo stays null. */
    void wantPhoto(String hex, String registration, String description) {
        if (hex.equals(photoHex)) return;
        photoHex = hex;
        photo = null;
        photoDetails = null;
        photoThread.execute(() -> {
            if (!hex.equals(photoHex)) return;
            // Pictures of this very aircraft first; a picture of one like it only if there is none.
            AirData.Photo[] found = new AirData.Photo[1];
            Bitmap bitmap = firstPhoto(found, hex, registration, description);
            if (bitmap != null && hex.equals(photoHex)) {
                photoDetails = found[0];
                photo = bitmap;
            }
        });
    }

    private Bitmap firstPhoto(AirData.Photo[] found, String hex, String registration, String description) {
        File file = new File(dir, "air-photo.feed");
        String upper = hex.toUpperCase(Locale.ROOT);
        try {
            Store.download(PHOTO + upper, file);
            found[0] = AirData.parsePhoto(Store.read(file));
            Bitmap bitmap = load(found[0]);
            if (bitmap != null) return bitmap;
        } catch (Exception e) {
            // On to the next source: a site not knowing an aircraft answers 404.
        }
        for (String url : new String[]{PLANESPOTTERS_HEX + upper, registration.isEmpty() ? "" : PLANESPOTTERS_REG + registration}) {
            if (url.isEmpty()) continue;
            try {
                Store.download(url, file);
                found[0] = AirData.parsePlanespotters(Store.read(file));
                Bitmap bitmap = load(found[0]);
                if (bitmap != null) return bitmap;
            } catch (Exception e) {
                // On to the next source.
            }
        }
        String name = description;
        try {
            Store.download(AIRCRAFT + hex, file);
            AirData.AircraftInfo info = AirData.parseAircraft(Store.read(file));
            if (info != null && !info.name.isEmpty()) name = info.name;
        } catch (Exception e) {
            // Keep the description the live feed gave, if any.
        }
        if (name.isEmpty()) {
            try {
                Store.download(HEXDB + hex, file);
                name = AirData.parseHexdbName(Store.read(file));
            } catch (Exception e) {
                // Nobody knows the type, so there is nothing to search for.
            }
        }
        for (String search : AirData.photoSearches(name)) {
            try {
                AirData.Photo known = similarPhotos.get(search);
                if (known == null) {
                    Store.download(COMMONS_START + java.net.URLEncoder.encode("\"" + search + "\" filetype:bitmap", "UTF-8") + COMMONS_END, file);
                    known = AirData.parseCommons(Store.read(file));
                }
                if (known != null) {
                    similarPhotos.put(search, known);
                    found[0] = known;
                    Bitmap bitmap = load(known);
                    if (bitmap != null) return bitmap;
                }
            } catch (Exception e) {
                // Try a broader search.
            }
        }
        return null;
    }

    /** The larger copy, or the small one if that fails: blurry at this size, but better than none. */
    private Bitmap load(AirData.Photo found) {
        if (found == null) return null;
        Bitmap bitmap = found.full.isEmpty() ? null : loadPhoto(found.full);
        return bitmap != null || found.thumbnail.isEmpty() ? bitmap : loadPhoto(found.thumbnail);
    }

    private Bitmap loadPhoto(String url) {
        try {
            File file = new File(dir, "air-photo.jpg");
            Store.download(url, file);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int sample = 1;
            while (options.outWidth / (sample * 2) >= PHOTO_WIDTH) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (IOException e) {
            return null;
        }
    }

    /** Details of one airframe, if known yet; the first ask starts the lookup. */
    AirData.AircraftInfo aircraft(String hex) {
        AirData.AircraftInfo known = aircraft.get(hex);
        if (known != null || !aircraftAsked.add(hex)) return known;
        planeThread.execute(() -> {
            try {
                File file = new File(dir, "air-aircraft.feed");
                Store.download(AIRCRAFT + hex, file);
                AirData.AircraftInfo info = AirData.parseAircraft(Store.read(file));
                if (info != null) aircraft.put(hex, info);
            } catch (Exception e) {
                // Not every airframe is known: the card just shows its type code.
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
