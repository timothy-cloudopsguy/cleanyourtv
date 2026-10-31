package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every feed and what it last said. Answers are kept as files in the app's cache, so a reopen shows something at once
 * and a feed that fails keeps its last good answer. Runs only between start() and stop(): while the app is on screen.
 */
final class Store {
    private static final long MINUTE = 60_000;
    private static final long HOUR = 60 * MINUTE;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int KEV_KEPT = 20;
    private static final int KP_READINGS = 24;
    private static final int HEADLINES_PER_SOURCE = 12;
    private static final String USER_AGENT = "OpsDeck/1.0 (Android TV add-on)";

    static final int VULNS = 0;
    static final int TECH = 1;
    static final int SCIENCE = 2;
    static final int HISTORY = 3;
    static final String[] EMERGENCY_CODES = {"7700", "7600", "7500"};
    static final String[] CATEGORIES = {"VULNS", "TECH", "SCIENCE", "HISTORY"};

    /** One vendor's status page. */
    static final class Service {
        static final int HISTORY_SIZE = 40;
        final String name;
        volatile String indicator = "unknown";
        volatile String description = "waiting for data";
        volatile Parsers.Summary summary;
        final int[] latency = new int[HISTORY_SIZE];
        volatile int samples;

        Service(String name) {
            this.name = name;
        }

        synchronized void addLatency(int ms) {
            latency[samples % HISTORY_SIZE] = ms;
            samples++;
        }

        int lastLatency() {
            return samples == 0 ? -1 : latency[(samples - 1) % HISTORY_SIZE];
        }
    }

    /** One fetch, shown in the System screen's feed table. */
    final class Job {
        final String name;
        final String url;
        final long ttl;
        final Parse parse;
        final File file;
        volatile long lastGood;
        volatile String error = "";
        volatile int lastMs = -1;
        private long due;
        private boolean loaded;

        Job(String name, String url, long ttl, Parse parse) {
            this.name = name;
            this.url = url;
            this.ttl = ttl;
            this.parse = parse;
            this.file = new File(cacheDir, name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-") + ".feed");
        }

        void run(long now) {
            boolean fresh = file.isFile() && now - file.lastModified() < ttl;
            if (!fresh) {
                long started = System.currentTimeMillis();
                try {
                    download(url, file);
                    lastMs = (int) (System.currentTimeMillis() - started);
                    due = now + ttl;
                    if (parse(file, lastMs)) {
                        error = "";
                        lastGood = file.lastModified();
                        loaded = true;
                    }
                    return;
                } catch (IOException e) {
                    String message = e.getMessage();
                    error = message != null && message.startsWith("HTTP") ? message : "offline";
                    android.util.Log.w("OpsDeck", name + ": " + e);
                    if (loaded || !file.isFile()) {
                        due = now + Math.min(ttl, MINUTE);
                        return;
                    }
                }
            }
            // Fresh enough from the last run, or the network failed and this is all we have.
            if (!loaded && parse(file, -1)) {
                loaded = true;
                lastGood = file.lastModified();
            }
            due = fresh ? file.lastModified() + ttl : now + Math.min(ttl, MINUTE);
        }

        private boolean parse(File f, int ms) {
            try {
                parse.apply(f, ms);
                return true;
            } catch (Exception e) {
                error = "bad answer";
                return false;
            }
        }
    }

    interface Parse {
        void apply(File file, int ms) throws Exception;
    }

    final List<Service> services = new ArrayList<>();
    final List<Job> jobs = new ArrayList<>();
    final String githubUser;
    final String watchRepo;

    volatile List<Parsers.Quake> quakes = Collections.emptyList();
    volatile float[] kp = new float[0];
    volatile Parsers.Iss iss;
    volatile Parsers.Calendar calendar;
    volatile List<Parsers.Repo> repos;
    volatile int[] weeks;
    volatile int kevTotal = -1;
    private volatile List<VolcanoData.Report> elevatedVolcanoes = Collections.emptyList();
    private volatile List<VolcanoData.Report> weeklyVolcanoes = Collections.emptyList();
    private volatile String elevatedJson = "";
    private volatile String locatedJson = "[]";
    /** Aircraft in the air in OpenSky's last world snapshot; -1 until there is one. */
    volatile int flightsUp = -1;
    /** Emergency squawks anywhere in the world, across the codes in EMERGENCY_CODES. */
    volatile List<AirData.Plane> emergencies = Collections.emptyList();
    /** Fed by both world snapshots, the Bridge's and the air map's, so the oceans are filling before the map is opened. */
    final AirData.Estimates estimates = new AirData.Estimates(WorldMap::isWater);
    @SuppressWarnings("unchecked")
    private final List<AirData.Plane>[] emergenciesByCode = new List[EMERGENCY_CODES.length];
    /** Which of the last ISS fixes to draw as a trail: lat, lon pairs, oldest first. */
    final List<double[]> issTrail = Collections.synchronizedList(new ArrayList<>());

    @SuppressWarnings("unchecked")
    private final List<List<Headline>>[] sources = new List[CATEGORIES.length];
    @SuppressWarnings("unchecked")
    private final List<Headline>[] categories = new List[CATEGORIES.length];

    private final File cacheDir;
    private ScheduledExecutorService pool;

    Store(Context context, String githubUser, String watchRepo) {
        this.cacheDir = context.getCacheDir();
        this.githubUser = githubUser;
        this.watchRepo = watchRepo;
        for (int i = 0; i < CATEGORIES.length; i++) {
            sources[i] = new ArrayList<>();
            categories[i] = Collections.emptyList();
        }
        addStatus("GitHub", "https://www.githubstatus.com");
        addStatus("Cloudflare", "https://www.cloudflarestatus.com");
        addStatus("Claude", "https://status.claude.com");
        addStatus("OpenAI", "https://status.openai.com");
        addStatus("Discord", "https://discordstatus.com");

        addKev();
        addFeed(VULNS, "The Hacker News", "https://feeds.feedburner.com/TheHackersNews");
        addFeed(VULNS, "Krebs on Security", "https://krebsonsecurity.com/feed/");
        addFeed(TECH, "Hacker News", "https://hnrss.org/frontpage?description=0");
        addFeed(TECH, "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index");
        addFeed(SCIENCE, "Quanta", "https://www.quantamagazine.org/feed/");
        addFeed(SCIENCE, "NASA", "https://www.nasa.gov/feed/");
        addFeed(SCIENCE, "ScienceDaily", "https://www.sciencedaily.com/rss/top/science.xml");
        addOnThisDay();
        addFeed(HISTORY, "Smithsonian", "https://www.smithsonianmag.com/rss/history/");

        jobs.add(new Job("USGS quakes", "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson",
                5 * MINUTE, (f, ms) -> quakes = Parsers.quakes(read(f))));
        jobs.add(new Job("NOAA Kp", "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json",
                15 * MINUTE, (f, ms) -> kp = Parsers.kp(read(f), KP_READINGS)));
        jobs.add(new Job("ISS", "https://api.wheretheiss.at/v1/satellites/25544", 15_000, (f, ms) -> {
            Parsers.Iss now = Parsers.iss(read(f));
            iss = now;
            issTrail.add(new double[]{now.lat, now.lon});
            if (issTrail.size() > 60) issTrail.remove(0);
        }));
        // OpenSky allows 400 credits a day and a world snapshot costs 4: at this pace a TV left on all day stays well
        // inside that, with room for the air map's own use.
        jobs.add(new Job("OpenSky count", "https://opensky-network.org/api/states/all", 20 * MINUTE,
                (f, ms) -> {
                    long now = System.currentTimeMillis();
                    List<AirData.Plane> world = AirData.parseOpenSky(read(f), now);
                    flightsUp = world.size();
                    estimates.update(world, now);
                }));
        for (int i = 0; i < EMERGENCY_CODES.length; i++) {
            int code = i;
            emergenciesByCode[i] = Collections.emptyList();
            jobs.add(new Job("Squawk " + EMERGENCY_CODES[i], "https://api.adsb.lol/v2/sqk/" + EMERGENCY_CODES[i], MINUTE,
                    (f, ms) -> setEmergencies(code, AirData.parseRegional(read(f), System.currentTimeMillis()))));
        }
        jobs.add(new Job("USGS alerts", "https://volcanoes.usgs.gov/hans-public/api/volcano/getElevatedVolcanoes", 15 * MINUTE,
                (f, ms) -> {
                    elevatedJson = read(f);
                    elevatedVolcanoes = VolcanoData.parseElevated(elevatedJson, locatedJson);
                }));
        jobs.add(new Job("USGS alert details", "https://volcanoes.usgs.gov/hans-public/api/volcano/getCapElevated", 15 * MINUTE,
                (f, ms) -> {
                    locatedJson = read(f);
                    if (!elevatedJson.isEmpty()) elevatedVolcanoes = VolcanoData.parseElevated(elevatedJson, locatedJson);
                }));
        // Published weekly, so a few looks a day is plenty.
        jobs.add(new Job("Smithsonian weekly", "https://volcano.si.edu/news/WeeklyVolcanoRSS.xml", 6 * HOUR,
                (f, ms) -> weeklyVolcanoes = VolcanoData.parseWeekly(read(f))));
        jobs.add(new Job("GitHub chart", "https://github.com/users/" + githubUser + "/contributions",
                HOUR, (f, ms) -> calendar = Parsers.calendar(read(f))));
        // Unauthenticated GitHub allows 60 calls an hour, so these stay slow.
        jobs.add(new Job("GitHub repos", "https://api.github.com/users/" + githubUser + "/repos?per_page=100&sort=pushed",
                30 * MINUTE, (f, ms) -> repos = Parsers.repos(read(f))));
        jobs.add(new Job("Repo commits", "https://api.github.com/repos/" + watchRepo + "/stats/commit_activity",
                6 * HOUR, (f, ms) -> weeks = Parsers.weeklyCommits(read(f))));
    }

    void start() {
        if (pool != null) return;
        pool = Executors.newScheduledThreadPool(2);
        pool.scheduleWithFixedDelay(this::tick, 0, 5, TimeUnit.SECONDS);
    }

    void stop() {
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    /** Raised US alerts first, then the week's reports from around the world. */
    List<VolcanoData.Report> volcanoReports() {
        List<VolcanoData.Report> all = new ArrayList<>(elevatedVolcanoes);
        all.addAll(weeklyVolcanoes);
        return all;
    }

    List<Headline> headlines(int category) {
        synchronized (categories) {
            return categories[category];
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        ScheduledExecutorService p = pool;
        for (Job job : jobs) {
            if (job.due > now || p == null) continue;
            job.due = Long.MAX_VALUE;
            try {
                p.execute(() -> job.run(System.currentTimeMillis()));
            } catch (RuntimeException stopped) {
                return;
            }
        }
    }

    private synchronized void setEmergencies(int code, List<AirData.Plane> found) {
        emergenciesByCode[code] = found;
        List<AirData.Plane> all = new ArrayList<>();
        for (List<AirData.Plane> list : emergenciesByCode) all.addAll(list);
        emergencies = all;
    }

    private void addStatus(String name, String base) {
        Service service = new Service(name);
        services.add(service);
        // The small status.json often, for the light and the latency; the full summary, up to 220 KB, less often.
        jobs.add(new Job(name + " status", base + "/api/v2/status.json", 30_000, (f, ms) -> {
            String[] s = Parsers.status(read(f));
            service.indicator = s[0];
            service.description = s[1];
            if (ms >= 0) service.addLatency(ms);
        }));
        jobs.add(new Job(name + " summary", base + "/api/v2/summary.json", 5 * MINUTE,
                (f, ms) -> service.summary = Parsers.summary(read(f))));
    }

    private void addFeed(int category, String name, String url) {
        List<Headline> slot = new ArrayList<>();
        sources[category].add(slot);
        jobs.add(new Job(name, url, 20 * MINUTE, (f, ms) -> {
            try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                replace(category, slot, FeedParser.parse(in, name).items, name);
            }
        }));
    }

    private void addOnThisDay() {
        List<Headline> slot = new ArrayList<>();
        sources[HISTORY].add(slot);
        Calendar today = Calendar.getInstance();
        String path = String.format(Locale.US, "%02d/%02d", today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH));
        jobs.add(new Job("Wikipedia", "https://en.wikipedia.org/api/rest_v1/feed/onthisday/selected/" + path,
                6 * HOUR, (f, ms) -> replace(HISTORY, slot, Parsers.onThisDay(read(f)), null)));
    }

    private void addKev() {
        List<Headline> slot = new ArrayList<>();
        sources[VULNS].add(slot);
        jobs.add(new Job("CISA KEV", "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json",
                6 * HOUR, (f, ms) -> replace(VULNS, slot, readKev(f), null)));
    }

    /** Sets a source's items, under its own name rather than the feed's title. */
    private void replace(int category, List<Headline> slot, List<Headline> items, String name) {
        synchronized (categories) {
            slot.clear();
            for (Headline h : items) {
                slot.add(name == null ? h : new Headline(name, h.title, h.summary, h.time, h.link));
            }
            categories[category] = Parsers.interleave(sources[category], HEADLINES_PER_SOURCE);
        }
    }

    /** The catalogue is near 2 MB, so it is read as a stream and only the newest entries are kept. */
    private List<Headline> readKev(File file) throws IOException {
        List<String[]> kept = new ArrayList<>();
        int total = 0;
        try (JsonReader json = new JsonReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            json.beginObject();
            while (json.hasNext()) {
                if (!"vulnerabilities".equals(json.nextName())) {
                    json.skipValue();
                    continue;
                }
                json.beginArray();
                while (json.hasNext()) {
                    kept.add(readKevEntry(json));
                    total++;
                    if (kept.size() > KEV_KEPT * 2) {
                        Collections.sort(kept, (a, b) -> b[2].compareTo(a[2]));
                        kept.subList(KEV_KEPT, kept.size()).clear();
                    }
                }
                json.endArray();
            }
            json.endObject();
        }
        Collections.sort(kept, (a, b) -> b[2].compareTo(a[2]));
        kevTotal = total;
        List<Headline> out = new ArrayList<>();
        for (int i = 0; i < Math.min(KEV_KEPT, kept.size()); i++) {
            String[] v = kept.get(i);
            String ransom = "Known".equalsIgnoreCase(v[5]) ? " Used in ransomware campaigns." : "";
            out.add(new Headline("CISA KEV", v[0] + ": " + v[1], v[3] + ransom + " Patch by " + v[4] + ".",
                    Parsers.isoTime(v[2] + "T00:00:00Z"), Parsers.kevLink(v[6], v[0])));
        }
        return out;
    }

    /** cve, name, dateAdded, description, dueDate, ransomware use, notes. */
    private static String[] readKevEntry(JsonReader json) throws IOException {
        String[] v = {"", "", "", "", "", "", ""};
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            if (json.peek() != JsonToken.STRING) {
                json.skipValue();
                continue;
            }
            String value = json.nextString();
            switch (key) {
                case "cveID": v[0] = value; break;
                case "vulnerabilityName": v[1] = value; break;
                case "dateAdded": v[2] = value; break;
                case "shortDescription": v[3] = value; break;
                case "dueDate": v[4] = value; break;
                case "knownRansomwareCampaignUse": v[5] = value; break;
                case "notes": v[6] = value; break;
                default: break;
            }
        }
        json.endObject();
        return v;
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void download(String url, File target) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        try {
            // 202 from GitHub's stats means "still computing": not an answer yet.
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            File part = new File(target.getPath() + ".part");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("too large");
                    out.write(buffer, 0, n);
                }
            }
            if (!part.renameTo(target)) throw new IOException("rename failed");
        } finally {
            c.disconnect();
        }
    }
}
