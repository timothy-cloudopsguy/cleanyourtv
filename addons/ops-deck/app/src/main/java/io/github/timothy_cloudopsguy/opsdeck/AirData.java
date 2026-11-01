package io.github.timothy_cloudopsguy.opsdeck;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Aircraft and the map's arithmetic, without Android so the tests can run it. Positions are "world" coordinates: Web
 * Mercator from 0 to 1, x eastward from 180 W, y southward from the top of the map. Velocities are world units a second,
 * so moving every aircraft each frame costs two multiplications.
 */
final class AirData {
    private static final double EARTH_METRES = 40_075_016.0;
    private static final double KNOTS_TO_MS = 0.514444;
    private static final double MS_TO_KNOTS = 1 / KNOTS_TO_MS;
    private static final double METRES_TO_FEET = 3.28084;
    private static final double MAX_LAT = 85.0;
    private static final long MAX_DRIFT_MS = 180_000;
    /** Past this, an aircraft out of range could be far from a straight line, so it is no longer guessed at. */
    private static final long ESTIMATE_MAX_MS = 90 * 60_000;
    private static final int ESTIMATE_LIMIT = 1500;
    private static final int CRUISE_MIN_FEET = 20_000;
    private static final int CRUISE_MIN_KNOTS = 250;
    private static final int DESCENDING_FPM = -800;
    private static final double STALE_SECONDS = 60;
    private static final long TRACK_MAX_AGE_MS = 30 * 60_000;
    private static final int MAX_TRACK_POINTS = 500;
    private static final int TAKEOFF_MAX_FEET = 5_000;

    private AirData() {}

    static final class Plane {
        final String hex;
        final String callsign;
        final String type;
        final String registration;
        final boolean ground;
        final int altitudeFeet;
        final int speedKnots;
        final float track;
        final int climbFpm;
        final String squawk;
        final boolean military;
        final double worldX;
        final double worldY;
        final double velocityX;
        final double velocityY;
        final long timeMs;
        final String category;
        /** Which of Aircraft's shapes this is. */
        final int kind;
        /** No receiver has heard it since timeMs: this is where it should be, not where it was reported. */
        final boolean estimated;
        // Only some aircraft broadcast these, and the world snapshot has none of them.
        /** The altitude set on the autopilot; 0 when not reported. */
        int targetFeet;
        /** The live feed's description of the type, where it gives one, as in "BOMBARDIER Regional Jet CRJ-700". */
        String description = "";
        /** Direction the wind blows from; -1 when not reported. */
        int windFrom = -1;
        int windKnots = -1;
        double mach;

        Plane(String hex, String callsign, String type, String registration, boolean ground, int altitudeFeet,
                int speedKnots, float track, int climbFpm, String squawk, boolean military,
                double lat, double lon, long timeMs, String category) {
            this(hex, callsign, type, registration, ground, altitudeFeet, speedKnots, track, climbFpm, squawk, military,
                    lat, lon, timeMs, category, false);
        }

        private Plane(String hex, String callsign, String type, String registration, boolean ground, int altitudeFeet,
                int speedKnots, float track, int climbFpm, String squawk, boolean military,
                double lat, double lon, long timeMs, String category, boolean estimated) {
            this.category = category;
            this.kind = Aircraft.kind(type, category);
            this.estimated = estimated;
            this.hex = hex;
            this.callsign = callsign;
            this.type = type;
            this.registration = registration;
            this.ground = ground;
            this.altitudeFeet = altitudeFeet;
            this.speedKnots = speedKnots;
            this.track = track;
            this.climbFpm = climbFpm;
            this.squawk = squawk;
            this.military = military;
            this.worldX = worldX(lon);
            this.worldY = worldY(lat);
            this.timeMs = timeMs;
            // Mercator stretches distance by 1/cos(lat), the same in both directions.
            double metresPerSecond = ground ? 0 : speedKnots * KNOTS_TO_MS;
            double scale = metresPerSecond / (EARTH_METRES * Math.max(0.05, Math.cos(Math.toRadians(lat))));
            this.velocityX = scale * Math.sin(Math.toRadians(track));
            this.velocityY = -scale * Math.cos(Math.toRadians(track));
        }

        /** Where it should be by now, flown on from its last report; never further than three minutes on. */
        double xAt(long now) {
            return worldX + velocityX * drift(now);
        }

        double yAt(long now) {
            return worldY + velocityY * drift(now);
        }

        private double drift(long now) {
            return Math.max(0, Math.min(estimated ? ESTIMATE_MAX_MS : MAX_DRIFT_MS, now - timeMs)) / 1000.0;
        }

        /** The same aircraft, flown on for longer. An old emergency code is dropped: it is not known to still apply. */
        Plane asEstimate() {
            return new Plane(hex, callsign, type, registration, false, altitudeFeet, speedKnots, track, climbFpm, "", military,
                    lat(worldY), lon(worldX), timeMs, category, true);
        }

        boolean cruising() {
            return !ground && altitudeFeet >= CRUISE_MIN_FEET && speedKnots >= CRUISE_MIN_KNOTS && climbFpm > DESCENDING_FPM;
        }

        boolean emergency() {
            return "7500".equals(squawk) || "7600".equals(squawk) || "7700".equals(squawk);
        }

        /** The name to show: the callsign, or else the registration, or else the transponder code. */
        String name() {
            if (!callsign.isEmpty()) return callsign;
            return registration.isEmpty() ? hex.toUpperCase(java.util.Locale.ROOT) : registration;
        }
    }

    static final class Route {
        final String airline;
        final String from;
        final String to;
        /** {lat, lon}, or null when adsbdb does not give them. */
        final double[] fromPoint;
        final double[] toPoint;
        /** The airports' ICAO codes, for their weather; empty when not given. */
        final String fromIcao;
        final String toIcao;

        Route(String airline, String from, String to, double[] fromPoint, double[] toPoint) {
            this(airline, from, to, fromPoint, toPoint, "", "");
        }

        Route(String airline, String from, String to, double[] fromPoint, double[] toPoint, String fromIcao, String toIcao) {
            this.fromIcao = fromIcao;
            this.toIcao = toIcao;
            this.airline = airline;
            this.from = from;
            this.to = to;
            this.fromPoint = fromPoint;
            this.toPoint = toPoint;
        }
    }

    private static final String FR24 = "https://www.youtube.com/@Flightradar24DotCom/streams";
    private static final String AIRLINE_VIDEOS = "https://www.youtube.com/@AirlineVideosLivePlus/streams";

    /** Plane spotters' live streams. Each was checked live and allowed to embed on 1 Nov 2026; streams that end each day are left out. */
    static final VolcanoData.Cam[] SPOTTER_CAMS = {
            VolcanoData.stream("n4I0d44oBEs", "Los Angeles LAX", "Runways 24L and 24R", "AirlineVideosLive+", AIRLINE_VIDEOS),
            VolcanoData.stream("KzsNnyN8D_Q", "Los Angeles LAX", "Runways 25L and 25R", "AirlineVideosLive+", AIRLINE_VIDEOS),
            VolcanoData.stream("uDBYuEV4zjI", "London Heathrow", "BIG JET TV", "BIG JET TV", "https://www.youtube.com/@BigJetTV/streams"),
            VolcanoData.stream("SgvpyjQX_HM", "London Heathrow", "Flightradar24 camera", "Flightradar24", FR24),
            VolcanoData.stream("T_QsY01tIK0", "New York LaGuardia", "Flightradar24 camera", "Flightradar24", FR24),
            VolcanoData.stream("V7_orOtu-oo", "Las Vegas", "Runways 26L and 26R", "Flightradar24", FR24),
            VolcanoData.stream("2IQmpCXbOmM", "St Maarten SXM", "Maho Beach", "Flightradar24", FR24),
            VolcanoData.stream("iSeH45R-8R0", "St Maarten SXM", "Maho Beach, with the crowd", "SHOWME Caribbean", "https://www.youtube.com/@SHOWMECaribbean/streams"),
            VolcanoData.stream("_GUsXnlVJmo", "Miami", "Runway 9/27 with tower radio", "PTZtv", "https://www.youtube.com/@PTZtv/streams"),
            VolcanoData.stream("q2Dxx5704fQ", "Miami", "From the Embassy Suites", "Ozolio Live", "https://www.youtube.com/@ozolio_live/streams"),
            VolcanoData.stream("EiUyBIcG4fQ", "Chicago Midway", "EarthCam", "EarthCam", "https://www.youtube.com/@earthcam/streams"),
            VolcanoData.stream("46VybvVqUKw", "Atlanta", "Tower and runway view", "Southern Radar", "https://www.youtube.com/@southernradar/streams"),
    };
    static final String SPOTTER_START_WALL = "n4I0d44oBEs,uDBYuEV4zjI,2IQmpCXbOmM,T_QsY01tIK0,_GUsXnlVJmo,46VybvVqUKw";

    /** Answers whether a point is over open water. */
    interface Water {
        boolean at(double lat, double lon);
    }

    /**
     * Aircraft past the reach of any receiver. No free feed hears them mid-ocean, so one that drops out of the world
     * snapshot while cruising is flown on along its last track, for as long as that stays over water. Coming back into a
     * snapshot, reaching land or running out of time ends the estimate.
     */
    static final class Estimates {
        private final Water water;
        private List<Plane> lastCruising = Collections.emptyList();
        private int lastSize;
        volatile List<Plane> current = Collections.emptyList();

        Estimates(Water water) {
            this.water = water;
        }

        synchronized void update(List<Plane> snapshot, long now) {
            // A partial answer would look like thousands of aircraft vanishing at once.
            if (snapshot.size() < lastSize / 2) return;
            Set<String> heard = new HashSet<>();
            for (Plane p : snapshot) heard.add(p.hex);
            List<Plane> next = new ArrayList<>();
            for (Plane p : current) keep(p, heard, now, next);
            for (Plane p : lastCruising) keep(p.asEstimate(), heard, now, next);
            List<Plane> cruising = new ArrayList<>();
            for (Plane p : snapshot) if (p.cruising()) cruising.add(p);
            lastCruising = cruising;
            lastSize = snapshot.size();
            current = next.size() > ESTIMATE_LIMIT ? new ArrayList<>(next.subList(0, ESTIMATE_LIMIT)) : next;
        }

        private void keep(Plane p, Set<String> heard, long now, List<Plane> out) {
            if (now - p.timeMs > ESTIMATE_MAX_MS) return;
            if (!water.at(lat(p.yAt(now)), lon(p.xAt(now)))) return;
            // Also stops one aircraft being listed twice.
            if (heard.add(p.hex)) out.add(p);
        }
    }

    /** One list for the map: the military feed's report wins over a live one, and an estimate only fills a gap. */
    static List<Plane> merge(List<Plane> live, List<Plane> military, List<Plane> estimates) {
        Set<String> seen = new HashSet<>();
        List<Plane> out = new ArrayList<>(live.size() + military.size() + estimates.size());
        for (Plane p : military) if (seen.add(p.hex)) out.add(p);
        for (Plane p : live) if (seen.add(p.hex)) out.add(p);
        for (Plane p : estimates) if (seen.add(p.hex)) out.add(p);
        return out;
    }

    /** Every route on file for a callsign, best source first, and the airline's name when known. */
    static final class RouteSet {
        final String airline;
        final List<Route> legs;

        RouteSet(String airline, List<Route> legs) {
            this.airline = airline;
            this.legs = legs;
        }
    }

    /** A route that fits where the aircraft is and where it is heading. */
    static final int ROUTE_FITS = 0;
    /** The route on file, but this aircraft is flying it the other way, so the ends have been swapped. */
    static final int ROUTE_FLIPPED = 1;
    /** Along the route but heading neither way, as when vectored or holding: shown, but not counted on. */
    static final int ROUTE_UNSURE = 2;
    /** Nothing on file is anywhere near this flight. Flight numbers get reused, so this is common. */
    static final int ROUTE_NO_FIT = 3;

    static final class RouteCheck {
        /** Null when nothing fits. */
        final Route route;
        final int state;
        /** False when this is only the best guess so far, and worth trying again as the flight goes on. */
        final boolean sure;

        RouteCheck(Route route, int state) {
            this(route, state, true);
        }

        RouteCheck(Route route, int state, boolean sure) {
            this.route = route;
            this.state = state;
            this.sure = sure;
        }

        /** Good enough to work out a distance and landing time from. */
        boolean trusted() {
            return state == ROUTE_FITS || state == ROUTE_FLIPPED;
        }
    }

    private static final double ROUTE_DETOUR = 1.25;
    private static final double ROUTE_SLACK_NM = 60;
    /** Near an airport an aircraft can be pointing any way, so climbing or descending says more than its heading. */
    private static final double NEAR_AIRPORT_NM = 60;
    private static final int VERTICAL_FPM = 300;
    /** Closer than this and level, as in a holding pattern or the circuit, nothing about it says which way it is going. */
    private static final double AT_AIRPORT_NM = 15;
    private static final double HEADING_TOLERANCE = 70;

    /** Looked-up routes go by flight number alone, and are often wrong or backward: this tests each against the aircraft. */
    static RouteCheck checkRoute(List<Route> legs, double lat, double lon, double track, int climbFpm) {
        Route flipped = null;
        Route guess = null;
        Route unsure = null;
        for (Route leg : legs) {
            if (leg.fromPoint == null || leg.toPoint == null) continue;
            double fromNm = nauticalMiles(lat, lon, leg.fromPoint[0], leg.fromPoint[1]);
            double toNm = nauticalMiles(lat, lon, leg.toPoint[0], leg.toPoint[1]);
            double length = nauticalMiles(leg.fromPoint[0], leg.fromPoint[1], leg.toPoint[0], leg.toPoint[1]);
            if (fromNm + toNm > length * ROUTE_DETOUR + ROUTE_SLACK_NM) continue;
            if (Math.min(fromNm, toNm) < NEAR_AIRPORT_NM && Math.abs(climbFpm) >= VERTICAL_FPM) {
                // Climbing away from the nearer airport means it left there; descending means it is going there.
                boolean nearDestination = toNm <= fromNm;
                if (nearDestination == climbFpm < 0) return new RouteCheck(leg, ROUTE_FITS);
                if (flipped == null) flipped = flip(leg);
                continue;
            }
            if (Math.min(fromNm, toNm) < AT_AIRPORT_NM) {
                if (guess == null) guess = leg;
                continue;
            }
            if (turn(track, bearing(lat, lon, leg.toPoint[0], leg.toPoint[1])) <= HEADING_TOLERANCE) {
                return new RouteCheck(leg, ROUTE_FITS);
            }
            if (turn(track, bearing(lat, lon, leg.fromPoint[0], leg.fromPoint[1])) <= HEADING_TOLERANCE) {
                if (flipped == null) flipped = flip(leg);
            } else if (unsure == null) {
                unsure = leg;
            }
        }
        if (flipped != null) return new RouteCheck(flipped, ROUTE_FLIPPED);
        if (guess != null) return new RouteCheck(guess, ROUTE_FITS, false);
        if (unsure != null) return new RouteCheck(unsure, ROUTE_UNSURE);
        return new RouteCheck(null, ROUTE_NO_FIT);
    }

    private static Route flip(Route leg) {
        return new Route(leg.airline, leg.to, leg.from, leg.toPoint, leg.fromPoint, leg.toIcao, leg.fromIcao);
    }

    /** Initial great-circle bearing in degrees. */
    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double d = Math.toRadians(lon2 - lon1);
        double degrees = Math.toDegrees(Math.atan2(Math.sin(d) * Math.cos(p2),
                Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(d)));
        return (degrees + 360) % 360;
    }

    private static double turn(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    private static final double FAR_FROM_LANDING_NM = 100;
    private static final int APPROACH_MINUTES = 10;

    /** Roughly how fast this class cruises; 0 when it varies too much to assume. */
    static int cruiseKnots(int kind) {
        switch (kind) {
            case Aircraft.NARROW: case Aircraft.WIDE: case Aircraft.FOUR_JET: case Aircraft.REGIONAL: case Aircraft.BIZJET:
                return 430;
            case Aircraft.TURBOPROP:
                return 280;
            default:
                return 0;
        }
    }

    /**
     * Minutes to landing, up to the next five as it is only a rough guess; -1 if too slow to say. Far out, an aircraft
     * still climbing has not reached its cruising speed, and it will not fly straight in at the end either.
     */
    static int minutesToLand(double nm, int knots, int cruiseKnots) {
        if (knots < 100) return -1;
        boolean far = nm > FAR_FROM_LANDING_NM;
        double minutes = nm / (far ? Math.max(knots, cruiseKnots) : knots) * 60 + (far ? APPROACH_MINUTES : 0);
        return (int) (Math.ceil(minutes / 5) * 5);
    }

    /** "45M" or "2H 05M". */
    static String duration(long minutes) {
        return minutes < 60 ? minutes + "M" : String.format(java.util.Locale.US, "%dH %02dM", minutes / 60, minutes % 60);
    }

    /** Airline-sized aircraft have the cabin rule about laptops below 10,000 feet; a light aircraft does not. */
    static boolean airliner(Plane p) {
        return p.kind == Aircraft.NARROW || p.kind == Aircraft.WIDE || p.kind == Aircraft.FOUR_JET || p.kind == Aircraft.REGIONAL;
    }

    /** The latest observation at an airport, in a line for the card. */
    static final class Weather {
        final String line;
        /** VFR, MVFR, IFR or LIFR, the standard flying conditions; empty when not reported. */
        final String category;

        Weather(String line, String category) {
            this.line = line;
            this.category = category;
        }
    }

    /** aviationweather.gov's METAR answer for one airport; null when it has no report. */
    static Weather parseWeather(String json) throws JSONException {
        JSONArray reports = new JSONArray(json);
        if (reports.length() == 0) return null;
        JSONObject o = reports.getJSONObject(0);
        StringBuilder line = new StringBuilder();
        String category = o.optString("fltCat", "");
        if (o.has("temp") && !o.isNull("temp")) {
            line.append(Math.round(o.getDouble("temp") * 9 / 5 + 32)).append("F");
        }
        String wx = o.optString("wxString", "");
        if (!wx.isEmpty()) append(line, wx);
        if (o.has("wspd") && !o.isNull("wspd")) {
            append(line, "wind " + o.optInt("wspd") + (o.has("wgst") && !o.isNull("wgst") ? "G" + o.optInt("wgst") : "") + " kt");
        }
        String cover = o.optString("cover", "");
        if (!cover.isEmpty()) append(line, cover);
        return new Weather(line.toString(), category);
    }

    private static void append(StringBuilder line, String part) {
        if (line.length() > 0) line.append(", ");
        line.append(part);
    }

    /** A picture of an aircraft, with who took it and where it came from. */
    static final class Photo {
        final String thumbnail;
        /** A larger copy of the same picture; empty when there is none. */
        final String full;
        final String photographer;
        final String source;
        /** Of another aircraft of about the same type, found by searching; empty for a picture of this very aircraft. */
        final String licence;
        /** The picture's own page, when its source asks to be linked to; empty otherwise. */
        final String link;

        Photo(String thumbnail, String full, String photographer, String source, String licence) {
            this(thumbnail, full, photographer, source, licence, "");
        }

        Photo(String thumbnail, String full, String photographer, String source, String licence, String link) {
            this.link = link;
            this.thumbnail = thumbnail;
            this.full = full;
            this.photographer = photographer;
            this.source = source;
            this.licence = licence;
        }

        boolean similarOnly() {
            return !licence.isEmpty();
        }
    }

    /** airport-data.com, by transponder code; null when there is no picture. Pages and full-size files share a number, as in .../aircraft/photo/001912792. */
    static Photo parsePhoto(String json) throws JSONException {
        JSONArray data = new JSONObject(json).optJSONArray("data");
        if (data == null || data.length() == 0) return null;
        JSONObject o = data.getJSONObject(0);
        String image = o.optString("image", "");
        if (!image.startsWith("https://")) return null;
        String link = o.optString("link", "");
        String number = link.substring(link.lastIndexOf('/') + 1);
        String full = number.matches("\\d+") ? "https://image.airport-data.com/aircraft/" + number + ".jpg" : "";
        return new Photo(image, full, o.optString("photographer", ""), "airport-data.com", "");
    }

    /** planespotters.net, by transponder code or registration; null when there is no picture. */
    static Photo parsePlanespotters(String json) throws JSONException {
        JSONArray photos = new JSONObject(json).optJSONArray("photos");
        if (photos == null || photos.length() == 0) return null;
        JSONObject o = photos.getJSONObject(0);
        JSONObject small = o.optJSONObject("thumbnail");
        JSONObject large = o.optJSONObject("thumbnail_large");
        String thumbnail = small == null ? "" : small.optString("src", "");
        String full = large == null ? "" : large.optString("src", "");
        if (!thumbnail.startsWith("https://") && !full.startsWith("https://")) return null;
        return new Photo(thumbnail, full, o.optString("photographer", ""), "planespotters.net", "", o.optString("link", ""));
    }

    private static final java.util.regex.Pattern NOT_THE_AIRCRAFT = java.util.regex.Pattern.compile(
            "cockpit|interior|cabin|engine|seat|panel|gear|wheel|tail|wing|livery|model|logo|diagram|drawing|museum|crash|wreck",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern HTML_TAG = java.util.regex.Pattern.compile("<[^>]+>");

    /** Searches to try for a type, most exact first: "Boeing 737NG 7H4/W", then "Boeing 737NG". Never just the maker. */
    static List<String> photoSearches(String name) {
        List<String> out = new ArrayList<>();
        String[] words = name.trim().split("\\s+");
        for (int n = words.length; n >= Math.min(2, words.length); n--) {
            if (n == 0 || words[0].isEmpty()) break;
            out.add(String.join(" ", java.util.Arrays.copyOf(words, n)));
        }
        return out;
    }

    /** Wikimedia Commons' search answer: the best-ranked wide picture of a whole aircraft; null when there is none. */
    static Photo parseCommons(String json) throws JSONException {
        JSONObject query = new JSONObject(json).optJSONObject("query");
        JSONObject pages = query == null ? null : query.optJSONObject("pages");
        if (pages == null) return null;
        JSONObject best = null;
        for (java.util.Iterator<String> keys = pages.keys(); keys.hasNext(); ) {
            JSONObject page = pages.getJSONObject(keys.next());
            JSONArray info = page.optJSONArray("imageinfo");
            if (info == null || info.length() == 0 || NOT_THE_AIRCRAFT.matcher(page.optString("title", "")).find()) continue;
            JSONObject image = info.getJSONObject(0);
            if (image.optInt("width") <= image.optInt("height")) continue;
            if (!image.optString("thumburl", "").startsWith("https://")) continue;
            if (best == null || page.optInt("index", Integer.MAX_VALUE) < best.optInt("index", Integer.MAX_VALUE)) best = page;
        }
        if (best == null) return null;
        JSONObject image = best.getJSONArray("imageinfo").getJSONObject(0);
        JSONObject meta = image.optJSONObject("extmetadata");
        String artist = meta == null ? "" : metadata(meta, "Artist");
        String licence = meta == null ? "" : metadata(meta, "LicenseShortName");
        // Its licence is what lets it be shown, so without one it is not used.
        if (licence.isEmpty()) return null;
        return new Photo(image.getString("thumburl"), "", artist, "Wikimedia Commons", licence);
    }

    private static String metadata(JSONObject meta, String name) {
        JSONObject field = meta.optJSONObject(name);
        String value = field == null ? "" : field.optString("value", "");
        return HTML_TAG.matcher(value).replaceAll("").trim();
    }

    /**
     * Recent positions of every aircraft in the live feed, kept by the app itself: with no outside limit to reach, there is
     * a graph ready for most aircraft when they are picked, going back as far as they have been in the feed.
     */
    static final class History {
        private static final long SPACING_MS = 30_000;
        private static final long FORGET_MS = 15 * 60_000;
        private static final int MAX_SAMPLES = 120;
        private final java.util.Map<String, java.util.ArrayDeque<double[]>> samples = new java.util.HashMap<>();

        synchronized void add(List<Plane> planes, long now) {
            for (Plane p : planes) {
                if (p.ground || p.estimated) continue;
                java.util.ArrayDeque<double[]> list = samples.get(p.hex);
                if (list == null) {
                    list = new java.util.ArrayDeque<>();
                    samples.put(p.hex, list);
                } else if (now - (long) list.peekLast()[3] < SPACING_MS) {
                    continue;
                }
                list.addLast(new double[]{p.worldX, p.worldY, p.altitudeFeet, now});
                if (list.size() > MAX_SAMPLES) list.removeFirst();
            }
            for (java.util.Iterator<java.util.ArrayDeque<double[]>> it = samples.values().iterator(); it.hasNext(); ) {
                if (now - (long) it.next().peekLast()[3] > FORGET_MS) it.remove();
            }
        }

        /** Null until there are two positions. */
        synchronized Track track(String hex) {
            java.util.ArrayDeque<double[]> list = samples.get(hex);
            if (list == null || list.size() < 2) return null;
            int n = list.size();
            double[] xs = new double[n];
            double[] ys = new double[n];
            int[] feet = new int[n];
            long[] times = new long[n];
            int i = 0;
            for (double[] sample : list) {
                xs[i] = sample[0];
                ys[i] = sample[1];
                feet[i] = (int) sample[2];
                times[i++] = (long) sample[3];
            }
            return new Track(xs, ys, feet, 0, times[0], feet[0], times);
        }
    }

    /** A flight's altitude and ground speed so far, in equal slices of time, for a small graph. */
    static final class Profile {
        final int[] feet;
        final int[] knots;
        final int maxFeet;
        final int maxKnots;

        Profile(int[] feet, int[] knots, int maxFeet, int maxKnots) {
            this.feet = feet;
            this.knots = knots;
            this.maxFeet = maxFeet;
            this.maxKnots = maxKnots;
        }
    }

    private static final long PROFILE_MIN_STEP_MS = 15_000;
    private static final int PROFILE_MAX_KNOTS = 750;

    /**
     * Speed is worked out from the path between points, as the path has no speed of its own; points very close in time, or
     * implying an impossible speed, are position noise and left out. A slice with no reports holds the one before it.
     */
    static Profile profile(Track track, int slices) {
        int[] feet = new int[slices];
        int[] knots = new int[slices];
        double[] sum = new double[slices];
        int[] count = new int[slices];
        boolean[] heard = new boolean[slices];
        long start = track.times[0];
        long span = Math.max(1, track.times[track.times.length - 1] - start);
        for (int i = 0; i < track.times.length; i++) {
            int slice = (int) Math.min(slices - 1, (track.times[i] - start) * slices / span);
            feet[slice] = Math.max(feet[slice], track.feet[i]);
            heard[slice] = true;
            if (i == 0) continue;
            long ms = track.times[i] - track.times[i - 1];
            if (ms < PROFILE_MIN_STEP_MS) continue;
            double speed = nauticalMiles(lat(track.ys[i - 1]), lon(track.xs[i - 1]), lat(track.ys[i]), lon(track.xs[i])) * 3_600_000 / ms;
            if (speed <= PROFILE_MAX_KNOTS) {
                sum[slice] += speed;
                count[slice]++;
            }
        }
        int maxFeet = 0;
        int maxKnots = 0;
        // The very first slice has no earlier point to work out a speed from.
        if (count[0] == 0) {
            for (int i = 1; i < slices && knots[0] == 0; i++) {
                if (count[i] > 0) knots[0] = (int) (sum[i] / count[i]);
            }
        }
        for (int i = 0; i < slices; i++) {
            if (!heard[i] && i > 0) feet[i] = feet[i - 1];
            knots[i] = count[i] > 0 ? (int) (sum[i] / count[i]) : i > 0 ? knots[i - 1] : knots[0];
            maxFeet = Math.max(maxFeet, feet[i]);
            maxKnots = Math.max(maxKnots, knots[i]);
        }
        return new Profile(feet, knots, maxFeet, maxKnots);
    }

    /** One airframe's details from adsbdb. */
    static final class AircraftInfo {
        final String name;
        final String owner;

        AircraftInfo(String name, String owner) {
            this.name = name;
            this.owner = owner;
        }
    }

    /** Null when adsbdb does not know it. */
    static AircraftInfo parseAircraft(String json) throws JSONException {
        JSONObject response = new JSONObject(json).optJSONObject("response");
        JSONObject aircraft = response == null ? null : response.optJSONObject("aircraft");
        if (aircraft == null) return null;
        String maker = aircraft.optString("manufacturer", "");
        String type = aircraft.optString("type", "");
        return new AircraftInfo(maker.isEmpty() ? type : maker + " " + type, aircraft.optString("registered_owner", ""));
    }

    /** hexdb.io's name for an airframe, as in "Cessna 680A"; empty when it does not know. */
    static String parseHexdbName(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        String maker = o.optString("Manufacturer", "").trim();
        String type = o.optString("Type", "").trim();
        if (type.isEmpty()) return "";
        // It often gives the maker in both fields.
        return maker.isEmpty() || type.toLowerCase(java.util.Locale.ROOT).startsWith(maker.toLowerCase(java.util.Locale.ROOT)) ? type : maker + " " + type;
    }

    /** The VRS route data adsb.lol serves: a flight can have stops, so this is one route per leg. */
    static List<Route> parseVrsRoute(String json) throws JSONException {
        JSONArray airports = new JSONObject(json).optJSONArray("_airports");
        List<Route> legs = new ArrayList<>();
        if (airports == null) return legs;
        for (int i = 0; i + 1 < airports.length(); i++) {
            JSONObject from = airports.getJSONObject(i);
            JSONObject to = airports.getJSONObject(i + 1);
            legs.add(new Route("", vrsPlace(from), vrsPlace(to), vrsPoint(from), vrsPoint(to), from.optString("icao", ""), to.optString("icao", "")));
        }
        return legs;
    }

    private static String vrsPlace(JSONObject airport) {
        String code = airport.optString("iata", "");
        String town = airport.optString("location", "");
        return town.isEmpty() ? code : code + " " + town;
    }

    private static double[] vrsPoint(JSONObject airport) {
        if (!airport.has("lat") || !airport.has("lon")) return null;
        return new double[]{airport.optDouble("lat"), airport.optDouble("lon")};
    }

    /** The path a flight has flown so far, as world coordinates with the altitude at each point. */
    static final class Track {
        final double[] xs;
        final double[] ys;
        final int[] feet;
        final int flownNm;
        /** When the path begins, and how high: a low start means it saw the takeoff. */
        final long startMs;
        final int startFeet;
        /** When each point was reported. */
        final long[] times;

        Track(double[] xs, double[] ys, int[] feet, int flownNm, long startMs, int startFeet, long[] times) {
            this.times = times;
            this.xs = xs;
            this.ys = ys;
            this.feet = feet;
            this.flownNm = flownNm;
            this.startMs = startMs;
            this.startFeet = startFeet;
        }

        /** The path starts low enough to count as the takeoff, rather than coming into a receiver's range mid-flight. */
        boolean sawTakeoff() {
            return startFeet < TAKEOFF_MAX_FEET;
        }
    }

    static double worldX(double lon) {
        return (lon + 180) / 360;
    }

    static double worldY(double lat) {
        double clamped = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        double sin = Math.sin(Math.toRadians(clamped));
        return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
    }

    static double lon(double worldX) {
        return wrapX(worldX) * 360 - 180;
    }

    static double lat(double worldY) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * worldY))));
    }

    /** Into 0 to 1: the map goes round. */
    static double wrapX(double x) {
        return x - Math.floor(x);
    }

    /** The short way across the date line: into -0.5 to 0.5. */
    static double wrapDelta(double dx) {
        return dx - Math.floor(dx + 0.5);
    }

    static double clampY(double y) {
        return Math.max(worldY(MAX_LAT), Math.min(worldY(-MAX_LAT), y));
    }

    /** The positions of these times, earliest first. */
    static int[] oldestFirst(long[] times) {
        Integer[] order = new Integer[times.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(times[a], times[b]));
        int[] out = new int[order.length];
        for (int i = 0; i < out.length; i++) out[i] = order[i];
        return out;
    }

    /** Great-circle distance in nautical miles. */
    static double nauticalMiles(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 3440.065 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** The airborne aircraft nearest a point, leaving one out so pressing again hops on to the next; null when none. */
    static Plane nearest(List<Plane> planes, double x, double y, long now, String excludeHex) {
        Plane best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Plane p : planes) {
            if (p.ground || p.hex.equals(excludeHex)) continue;
            double dx = wrapDelta(p.xAt(now) - x);
            double dy = p.yAt(now) - y;
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = p;
            }
        }
        return best;
    }

    /** adsb.lol ("ac") and adsb.fi ("aircraft") answer in the same readsb format. */
    static List<Plane> parseRegional(String json, long now) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray list = root.optJSONArray("ac");
        if (list == null) list = root.optJSONArray("aircraft");
        List<Plane> out = new ArrayList<>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            if (!o.has("lat") || !o.has("lon")) continue;
            double seen = o.optDouble("seen_pos", 0);
            if (seen > STALE_SECONDS) continue;
            String category = o.optString("category", "");
            String type = o.optString("t", "");
            if (Aircraft.notAnAircraft(type, category)) continue;
            Object altitude = o.opt("alt_baro");
            boolean ground = "ground".equals(altitude);
            int feet = altitude instanceof Number ? ((Number) altitude).intValue() : 0;
            Plane plane = new Plane(o.optString("hex", ""), o.optString("flight", "").trim(), type,
                    o.optString("r", ""), ground, feet, (int) o.optDouble("gs", 0), (float) o.optDouble("track", 0),
                    o.optInt("baro_rate", 0), o.optString("squawk", ""), (o.optInt("dbFlags", 0) & 1) != 0,
                    o.getDouble("lat"), o.getDouble("lon"), now - (long) (seen * 1000), category);
            // The autopilot's value comes in steps that do not land on round numbers: 20,992 means 21,000.
            plane.targetFeet = (int) Math.round(o.optInt("nav_altitude_mcp", 0) / 100.0) * 100;
            plane.description = o.optString("desc", "");
            plane.windFrom = o.optInt("wd", -1);
            plane.windKnots = o.optInt("ws", -1);
            plane.mach = o.optDouble("mach", 0);
            out.add(plane);
        }
        return out;
    }

    /** OpenSky's world snapshot: positional arrays, metres and metres a second. Aircraft on the ground are left out. */
    static List<Plane> parseOpenSky(String json, long now) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray states = root.optJSONArray("states");
        // A reopened app reuses a snapshot up to two minutes old, so age is judged against the snapshot, not the clock.
        long snapshot = Math.min(now, root.optLong("time", now / 1000) * 1000);
        List<Plane> out = new ArrayList<>();
        if (states == null) return out;
        for (int i = 0; i < states.length(); i++) {
            JSONArray s = states.getJSONArray(i);
            if (s.isNull(5) || s.isNull(6) || s.optBoolean(8, false)) continue;
            long reported = s.isNull(3) ? snapshot : Math.min(snapshot, s.optLong(3) * 1000);
            if (snapshot - reported > STALE_SECONDS * 1000) continue;
            double metres = s.isNull(7) ? s.optDouble(13, 0) : s.optDouble(7, 0);
            out.add(new Plane(s.optString(0, ""), s.isNull(1) ? "" : s.optString(1, "").trim(), "", "", false,
                    (int) (metres * METRES_TO_FEET), (int) (s.optDouble(9, 0) * MS_TO_KNOTS),
                    (float) s.optDouble(10, 0), (int) (s.optDouble(11, 0) * METRES_TO_FEET * 60),
                    s.isNull(14) ? "" : s.optString(14, ""), false, s.getDouble(6), s.getDouble(5), reported, ""));
        }
        return out;
    }

    /** adsbdb's answer for a callsign; null when it has no route. Routes are looked up by flight number, so they can be out of date. */
    static Route parseRoute(String json) throws JSONException {
        JSONObject response = new JSONObject(json).optJSONObject("response");
        JSONObject route = response == null ? null : response.optJSONObject("flightroute");
        if (route == null) return null;
        JSONObject airline = route.optJSONObject("airline");
        JSONObject origin = route.optJSONObject("origin");
        JSONObject destination = route.optJSONObject("destination");
        return new Route(airline == null ? "" : airline.optString("name", ""), place(origin), place(destination),
                point(origin), point(destination), origin == null ? "" : origin.optString("icao_code", ""),
                destination == null ? "" : destination.optString("icao_code", ""));
    }

    private static double[] point(JSONObject airport) {
        if (airport == null || !airport.has("latitude") || !airport.has("longitude")) return null;
        return new double[]{airport.optDouble("latitude"), airport.optDouble("longitude")};
    }

    /**
     * OpenSky's track for an aircraft: rows of [time, lat, lon, baro metres, track, on ground]. Null when there is nothing
     * usable or the last point is too old to belong to the flight in the air now.
     */
    static Track parseTrack(String json, long now) throws JSONException {
        JSONArray path = new JSONObject(json).optJSONArray("path");
        if (path == null) return null;
        List<double[]> rows = new ArrayList<>();
        long last = 0;
        long start = 0;
        for (int i = 0; i < path.length(); i++) {
            JSONArray p = path.getJSONArray(i);
            if (p.isNull(1) || p.isNull(2)) continue;
            if (start == 0) start = p.optLong(0) * 1000;
            rows.add(new double[]{p.getDouble(1), p.getDouble(2), p.isNull(3) ? 0 : p.getDouble(3) * METRES_TO_FEET, p.optLong(0) * 1000});
            last = p.optLong(0) * 1000;
        }
        if (rows.size() < 2 || now - last > TRACK_MAX_AGE_MS) return null;
        int step = Math.max(1, rows.size() / MAX_TRACK_POINTS);
        int count = (rows.size() - 1) / step + 1;
        double[] xs = new double[count];
        double[] ys = new double[count];
        int[] feet = new int[count];
        long[] times = new long[count];
        double miles = 0;
        for (int i = 0; i < count; i++) {
            double[] row = rows.get(i * step);
            xs[i] = worldX(row[1]);
            ys[i] = worldY(row[0]);
            feet[i] = (int) row[2];
            times[i] = (long) row[3];
            if (i > 0) {
                double[] before = rows.get((i - 1) * step);
                miles += nauticalMiles(before[0], before[1], row[0], row[1]);
            }
        }
        return new Track(xs, ys, feet, (int) miles, start, (int) rows.get(0)[2], times);
    }

    /** The shortest path over the globe as n+1 {worldX, worldY} points: a curve on this map, as real routes are. */
    static double[][] greatCircle(double lat1, double lon1, double lat2, double lon2, int n) {
        double p1 = Math.toRadians(lat1);
        double l1 = Math.toRadians(lon1);
        double p2 = Math.toRadians(lat2);
        double l2 = Math.toRadians(lon2);
        double[] a = {Math.cos(p1) * Math.cos(l1), Math.cos(p1) * Math.sin(l1), Math.sin(p1)};
        double[] b = {Math.cos(p2) * Math.cos(l2), Math.cos(p2) * Math.sin(l2), Math.sin(p2)};
        double dot = Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
        double angle = Math.acos(dot);
        double[][] out = new double[n + 1][];
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            double wa = angle < 1e-9 ? 1 - t : Math.sin((1 - t) * angle) / Math.sin(angle);
            double wb = angle < 1e-9 ? t : Math.sin(t * angle) / Math.sin(angle);
            double x = wa * a[0] + wb * b[0];
            double y = wa * a[1] + wb * b[1];
            double z = wa * a[2] + wb * b[2];
            double lat = Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y)));
            out[i] = new double[]{worldX(Math.toDegrees(Math.atan2(y, x))), worldY(lat)};
        }
        return out;
    }

    static String squawkMeaning(String squawk) {
        switch (squawk) {
            case "7500": return "hijack";
            case "7600": return "radio failure";
            default: return "emergency";
        }
    }

    /** One line for the Bridge: the first emergency and how many more. */
    static String squawkSummary(List<Plane> emergencies) {
        if (emergencies.isEmpty()) return "No emergency squawks";
        Plane first = emergencies.get(0);
        String line = first.squawk + " " + squawkMeaning(first.squawk) + ": " + first.name();
        return emergencies.size() == 1 ? line : line + ", and " + (emergencies.size() - 1) + " more";
    }

    /** The next aircraft worth jumping to: emergencies, then military, each in a stable order, going round; null when none. */
    static Plane nextPriority(List<Plane> planes, String currentHex) {
        String current = "";
        for (Plane p : planes) {
            if (p.hex.equals(currentHex) && (p.emergency() || p.military)) current = priorityKey(p);
        }
        Plane first = null;
        Plane next = null;
        for (Plane p : planes) {
            if (!p.emergency() && !p.military) continue;
            String key = priorityKey(p);
            if (first == null || key.compareTo(priorityKey(first)) < 0) first = p;
            if (key.compareTo(current) > 0 && (next == null || key.compareTo(priorityKey(next)) < 0)) next = p;
        }
        return next != null ? next : first;
    }

    private static String priorityKey(Plane p) {
        return (p.emergency() ? "0" : "1") + p.hex;
    }

    static Plane find(List<Plane> planes, String hex) {
        for (int i = 0; i < planes.size(); i++) if (planes.get(i).hex.equals(hex)) return planes.get(i);
        return null;
    }

    /** Where the map's camera is, for working out what can be picked and where it is on screen. */
    static final class PickView {
        private static final float EDGE_MARGIN = 20;
        final double centerX;
        final double centerY;
        final double world;
        final float left;
        final float top;
        final float right;
        final float bottom;
        /** Too many aircraft to draw as shapes: only emergencies and military, which still are, can be picked. */
        final boolean shapesOnly;
        /** Screen rectangles under the HUD and cards, each left, top, right, bottom. */
        final float[][] covered;

        PickView(double centerX, double centerY, double world, float left, float top, float right, float bottom, boolean shapesOnly, float[][] covered) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.world = world;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.shapesOnly = shapesOnly;
            this.covered = covered;
        }

        float x(Plane p, long now) {
            return (float) ((left + right) / 2 + wrapDelta(p.xAt(now) - centerX) * world);
        }

        float y(Plane p, long now) {
            return (float) ((top + bottom) / 2 + (p.yAt(now) - centerY) * world);
        }

        boolean eligible(Plane p, long now) {
            if (p.ground || (shapesOnly && !p.emergency() && !p.military)) return false;
            float x = x(p, now);
            float y = y(p, now);
            if (x < left + EDGE_MARGIN || x > right - EDGE_MARGIN || y < top + EDGE_MARGIN || y > bottom - EDGE_MARGIN) return false;
            for (float[] box : covered) {
                if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) return false;
            }
            return true;
        }
    }

    private static final float HOP_MIN = 6;
    private static final float HOP_CONE = 1.2f;
    private static final float HOP_OFF_AXIS_COST = 2;
    private static final float PRIORITY_DISCOUNT = 0.6f;
    private static final float OVERLAP_PX = 6;

    private static float weight(Plane p) {
        return p.emergency() || p.military ? PRIORITY_DISCOUNT : 1;
    }

    /** The aircraft to highlight first: nearest the middle of the map, leaning toward emergencies and military. */
    static Plane pickStart(List<Plane> planes, PickView view, long now) {
        float mx = (view.left + view.right) / 2;
        float my = (view.top + view.bottom) / 2;
        Plane best = null;
        float bestCost = Float.MAX_VALUE;
        for (Plane p : planes) {
            if (!view.eligible(p, now)) continue;
            float cost = (float) Math.hypot(view.x(p, now) - mx, view.y(p, now) - my) * weight(p);
            if (best == null || cost < bestCost || (cost == bestCost && p.hex.compareTo(best.hex) < 0)) {
                best = p;
                bestCost = cost;
            }
        }
        return best;
    }

    /**
     * The aircraft one arrow press away: the cheapest in a cone about that direction, where being off to the side costs
     * twice as much as being far. With nothing that way, aircraft stacked on this one take turns, so none is out of reach.
     * Null when there is nothing to go to.
     */
    static Plane hop(List<Plane> planes, Plane from, int dx, int dy, PickView view, long now) {
        float fromX = view.x(from, now);
        float fromY = view.y(from, now);
        Plane best = null;
        float bestCost = Float.MAX_VALUE;
        Plane stackedNext = null;
        Plane stackedWrap = null;
        boolean forward = dx > 0 || dy > 0;
        for (Plane p : planes) {
            if (p.hex.equals(from.hex) || !view.eligible(p, now)) continue;
            float ox = view.x(p, now) - fromX;
            float oy = view.y(p, now) - fromY;
            float along = ox * dx + oy * dy;
            float across = Math.abs(ox * dy - oy * dx);
            if (along >= HOP_MIN && across <= HOP_CONE * along) {
                float cost = (along + HOP_OFF_AXIS_COST * across) * weight(p);
                if (best == null || cost < bestCost || (cost == bestCost && p.hex.compareTo(best.hex) < 0)) {
                    best = p;
                    bestCost = cost;
                }
            }
            if (Math.hypot(ox, oy) <= OVERLAP_PX) {
                int order = forward ? p.hex.compareTo(from.hex) : from.hex.compareTo(p.hex);
                if (order > 0 && (stackedNext == null || closer(p, stackedNext, forward))) stackedNext = p;
                if (order < 0 && (stackedWrap == null || closer(p, stackedWrap, forward))) stackedWrap = p;
            }
        }
        if (best != null) return best;
        return stackedNext != null ? stackedNext : stackedWrap;
    }

    private static boolean closer(Plane a, Plane b, boolean forward) {
        return forward ? a.hex.compareTo(b.hex) < 0 : a.hex.compareTo(b.hex) > 0;
    }

    private static String place(JSONObject airport) {
        if (airport == null) return "?";
        String code = airport.optString("iata_code", "");
        String town = airport.optString("municipality", "");
        return town.isEmpty() ? code : code + " " + town;
    }

    /** {lat, lon} from ipwho.is; null when it could not tell. */
    static double[] parseLocation(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.optBoolean("success", false) || !o.has("latitude") || !o.has("longitude")) return null;
        return new double[]{o.getDouble("latitude"), o.getDouble("longitude")};
    }
}
