# Mantra Trail

**Offline outdoor maps, a track that records itself to a file as you walk, a compass and a spirit
level. For the mountains in Croatia, on a phone with no signal.**

Mantra Productions · Marko Boško

---

## How to install

Every build is published as a release. Open this on the phone and tap the `.apk`:

**https://github.com/markoboskoauroville/MANTRA_TRAIL/releases/latest**

The two newest builds are kept and no more (`versioning.md` §4).

**The APK is built by GitHub Actions and never on a desk** (`android-app.md` §1a). Pushing to
`main` runs the gates, Test 1, the build, and publishes the release.

## The maps

| Layer | With no signal at all |
|---|---|
| **OpenAndroMaps** (a `.map` file you choose once) | everything: it is a file on the phone |
| **TK25 Hrvatska** — the state survey's official 1:25000, WMS, open licence | everything fetched before |
| **OpenTopoMap** | everything fetched before |
| **Google** | nothing, and it may not be otherwise |

Google's Maps terms forbid pre-fetching, caching or storing tiles and name offline use as a
prohibited case, so the Google layer is online only and says so. Caching it would be a map that
works at home and is empty on the mountain.

Get the offline map from https://www.openandromaps.org (Croatia), put it in a folder you keep, and
choose it with the file picker the first time you press the OpenAndroMaps layer.

## The track

GPX 1.1, written point by point as you walk and flushed every time, so a flat battery costs the
closing tags rather than the walk. Everything reads GPX: Garmin, Strava, Komoot, OsmAnd, Locus,
QGIS, Google Earth.

A fix is refused if its accuracy is worse than 50 m, if it implies more than 12 m/s, or if the
clock went backwards. **The number of refused fixes is on the screen**, because a track app that
hides what it threw away is asking to be trusted about the one thing nobody can check.

## The documents

| File | What it is for |
|---|---|
| `HANDOFF.md` | the finished state — a new chat picks the app up from this and nothing else |
| `DEVELOPMENT.md` | every decision and why, in the past tense |
| `DELIVERY_RECORD.md` | what was measured, and what was NOT tested |
