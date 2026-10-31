package io.github.timothy_cloudopsguy.opsdeck;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
    private static final double STALE_SECONDS = 60;
    private static final long TRACK_MAX_AGE_MS = 30 * 60_000;
    private static final int MAX_TRACK_POINTS = 500;

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

        Plane(String hex, String callsign, String type, String registration, boolean ground, int altitudeFeet,
                int speedKnots, float track, int climbFpm, String squawk, boolean military,
                double lat, double lon, long timeMs) {
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
            return Math.max(0, Math.min(MAX_DRIFT_MS, now - timeMs)) / 1000.0;
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

        Route(String airline, String from, String to, double[] fromPoint, double[] toPoint) {
            this.airline = airline;
            this.from = from;
            this.to = to;
            this.fromPoint = fromPoint;
            this.toPoint = toPoint;
        }
    }

    /** The path a flight has flown so far, as world coordinates with the altitude at each point. */
    static final class Track {
        final double[] xs;
        final double[] ys;
        final int[] feet;
        final int flownNm;

        Track(double[] xs, double[] ys, int[] feet, int flownNm) {
            this.xs = xs;
            this.ys = ys;
            this.feet = feet;
            this.flownNm = flownNm;
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
            Object altitude = o.opt("alt_baro");
            boolean ground = "ground".equals(altitude);
            int feet = altitude instanceof Number ? ((Number) altitude).intValue() : 0;
            out.add(new Plane(o.optString("hex", ""), o.optString("flight", "").trim(), o.optString("t", ""),
                    o.optString("r", ""), ground, feet, (int) o.optDouble("gs", 0), (float) o.optDouble("track", 0),
                    o.optInt("baro_rate", 0), o.optString("squawk", ""), (o.optInt("dbFlags", 0) & 1) != 0,
                    o.getDouble("lat"), o.getDouble("lon"), now - (long) (seen * 1000)));
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
                    s.isNull(14) ? "" : s.optString(14, ""), false, s.getDouble(6), s.getDouble(5), reported));
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
                point(origin), point(destination));
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
        for (int i = 0; i < path.length(); i++) {
            JSONArray p = path.getJSONArray(i);
            if (p.isNull(1) || p.isNull(2)) continue;
            rows.add(new double[]{p.getDouble(1), p.getDouble(2), p.isNull(3) ? 0 : p.getDouble(3) * METRES_TO_FEET});
            last = p.optLong(0) * 1000;
        }
        if (rows.size() < 2 || now - last > TRACK_MAX_AGE_MS) return null;
        int step = Math.max(1, rows.size() / MAX_TRACK_POINTS);
        int count = (rows.size() - 1) / step + 1;
        double[] xs = new double[count];
        double[] ys = new double[count];
        int[] feet = new int[count];
        double miles = 0;
        for (int i = 0; i < count; i++) {
            double[] row = rows.get(i * step);
            xs[i] = worldX(row[1]);
            ys[i] = worldY(row[0]);
            feet[i] = (int) row[2];
            if (i > 0) {
                double[] before = rows.get((i - 1) * step);
                miles += nauticalMiles(before[0], before[1], row[0], row[1]);
            }
        }
        return new Track(xs, ys, feet, (int) miles);
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

    /** The emergency after the one selected, in a stable order; null past the last one, or when there are none. */
    static Plane nextEmergency(List<Plane> planes, String currentHex) {
        Plane first = null;
        Plane next = null;
        for (Plane p : planes) {
            if (!p.emergency()) continue;
            if (first == null || p.hex.compareTo(first.hex) < 0) first = p;
            if (p.hex.compareTo(currentHex) > 0 && (next == null || p.hex.compareTo(next.hex) < 0)) next = p;
        }
        return currentHex.isEmpty() ? first : next;
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
