# Ops Deck

A network operations console for the TV, driven entirely with the D-pad: live feeds you can dig through, a curated
news desk, your GitHub chart, a row of your TV apps that plays a note as you scroll, and a game.

On cleanyourtv's Home it is a module card, **Ops Deck**. Up and Down on the rail change screen, Right or OK moves into
it, and Back or Left comes out again.

| Screen | What is on it |
|---|---|
| Bridge | Eight summary tiles (OK opens the screen behind each), including flights in the air and any emergency squawk, a headline ticker and the TV apps. Each app has its own note on a pentatonic scale, so scrolling the row plays a little tune; OK opens the app |
| Intel | Four desks. Vulns: CISA's known exploited list, The Hacker News, Krebs on Security. Tech: Hacker News, Ars Technica. Science: Quanta, NASA, ScienceDaily. History: Wikipedia's On This Day, Smithsonian. Each story has its summary and a QR code for your phone; OK reads it here in a small built-in browser |
| Status | GitHub, Cloudflare, Claude, OpenAI and Discord: status, response time from this TV as a trace, open incidents, degraded components |
| Planet | A dot-matrix world with the last day's M4.5+ earthquakes and the ISS with its trail; the planetary K index |
| Volcano | Activity: raised US alerts and the Smithsonian / USGS weekly report, also marked on Planet's map. Cams: 20 cameras, 12 of them live video. Wall: up to six favourites. Observatory cameras show current pictures; the first two other streams play muted live video in their tiles (a TV like this lists two video decoders); any others show the channel's poster and say so. OK plays a stream full screen in the TV's YouTube app |
| Air | A live air traffic map, starting where the TV is. Arrows move anywhere in the world; OK opens the menu (zoom, home, next emergency or military) and the channel keys zoom too. OK twice starts picking: the arrows then hop a highlight from aircraft to aircraft, and OK watches the one highlighted. Watching follows it and shows the path it has flown, coloured by altitude, a dashed line to its likely destination, its type, altitude and speed, and a QR code to track it. Emergency squawks (7500, 7600, 7700) pulse red at every zoom, and Next goes to them first. They are what a feed reports, and the card says so when the feeds disagree. Military aircraft are yellow at every zoom, as far as the feeds' databases list them. Each aircraft has an outline for its class, and the selected one a turning 3D model of that class. Free feeds hear nothing mid-ocean, so an aircraft that drops out while cruising is flown on along its last track for up to 90 minutes while that stays over water: these are hollow, marked EST, and are guesses |
| Git | The contribution chart with a day-by-day cursor, streaks, public repositories, and one watched repository's weekly commits |
| System | Memory, storage, live network use, the health of every feed, and the sound switch |
| Game | Incident Commander: fix alerts across a rack before they burn your error budget |

- Package: `io.github.timothy_cloudopsguy.opsdeck`
- Author: [@timothy-cloudopsguy](https://github.com/timothy-cloudopsguy)
- Tested on: Hisense SmartTV 4K (songshan), Android 11, 31 Oct 2026: 1,429 frames, 0.84% janky, 99th percentile 14 ms; about 43 MB of memory. Also the Android TV 12 emulator.

## Make it yours

- The GitHub account and watched repository: `github_user` and `github_watch_repo` in
  `app/src/main/res/values/config.xml`. The chart counts what GitHub shows the public, so private work appears only
  if "Private contributions" is on in that account's profile settings.
- The air map's start: `home_lat` and `home_lon` in the same file. Left empty, it asks ipwho.is once a month.
- The feeds: the `addFeed` lines in `Store.java`. Any RSS or Atom address over HTTPS works.

## Permissions

- `INTERNET`: fetches the public feeds above over HTTPS, without accounts or API keys, and sends nothing about you. The
  services see an ordinary request from your address. No telemetry and no ads.
- A `<queries>` entry for TV launcher activities, not a permission: it lists the apps for the Bridge's row.

The Volcano screen reads USGS and the Smithsonian's weekly report while the app is open, and fetches camera pictures from
USGS, the Alaska Volcano Observatory and YouTube only while they are on screen. Live streams belong to their channels (USGS,
AfarTV), play in the YouTube app under its own terms, and may move when a channel restarts one; the card then opens the channel.

The air map fetches only while it is on screen: adsb.lol or adsb.fi for live traffic around the map's centre, OpenSky for
the zoomed-out world view (every two minutes, within its free limit of 400 credits a day) and the Bridge's flight count (every 20 minutes while the app is open), adsbdb for a selected flight's
route, OpenSky for its flown path and OpenStreetMap for map tiles (kept in the app's cache so each is fetched once). To find where to start, ipwho.is works out a rough location
from the TV's internet address; only the latitude and longitude are kept, on the TV. adsb.fi (or adsb.lol if it fails) is asked each minute while the map is on screen for military aircraft worldwide. adsb.lol is also asked each minute while the app is open for emergency squawks worldwide. Those services receive the map
area or flight being looked at. The map shows what volunteer and research receivers hear, so it is not every flight, and
it is for fun, not for navigation or safety.

The built-in reader is a WebView. It loads the page you open from that site, so the site and whatever it embeds see
your address and can run their scripts, as in any browser. It opens https pages only and blocks other apps,
downloads and location; third-party cookies are off, and cookies and cached pages are cleared when it closes.

No background service: feeds refresh only while the screen is showing. The screen stays awake while the app is open
and the picture drifts a pixel or two each minute against burn-in, so press Back when you are done.

## Build and install

JDK 17 and Android SDK 34, as for the launcher. From the repo root:

```bash
tools/venv/bin/python -I tools/manage.py install ops-deck
```

with `TV_HOST=<tv ip>` set, or follow the steps in [`addons/README.md`](../README.md). To remove it:
`tools/venv/bin/python -I tools/tv.py 'pm uninstall io.github.timothy_cloudopsguy.opsdeck'`. Close ADB afterwards, as the
repo's guide describes.
