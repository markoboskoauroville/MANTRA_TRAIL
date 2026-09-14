# Mantra Trail — the finished state

    version      1
    repository   markoboskoauroville/MANTRA_TRAIL (public)
    artefact     1-mantra-trail-v1.apk, release tag v1
    built by     GitHub Actions only (android-app.md §1a). Never on a desk.

The reasons live in [`DEVELOPMENT.md`](DEVELOPMENT.md); what was and was not proved lives in
[`DELIVERY_RECORD.md`](DELIVERY_RECORD.md). This file is the present tense only.

## WHAT IT IS

A map that is still there when the signal is not, a red button that records the walk straight into
a GPX file, and a second face with a compass and a spirit level that both work with nothing
fetched. It exists for trekking in the Croatian mountains and for levelling a tripod in a forest.

## THE MODEL

    Fix                 lat, lon, ele, time, accuracy, satellites. Accuracy is never invented:
                        a fix that carries none is null and the screen draws a dash.
    TrackRules          what may join a track: accuracy ≤ 50 m, ≤ 12 m/s, time going forward,
                        ≥ 2 m of movement. Everything refused is counted and shown.
    Recording           running totals, so a nine-hour walk does not get slower as it goes.
    Trail               the one place the live state lives. The screen and the service both
                        hand fixes here; the same fix arriving twice is recognised by its own
                        timestamp and dropped.
    Gpx                 GPX 1.1, written point by point and flushed every time.

**The irreplaceable thing is the track being recorded.** It is written to the app's own folder as
it happens, and copied to the folder you chose only when it is finished. Nothing is held in memory
waiting for a stop that might not come.

## THE SCREENS

    the map         the whole interface from the first frame: crosshair fixed at centre, the
                    coordinates and accuracy above, the walk's totals below, four layers as a
                    radio row, five controls that never change shape.
    the tools       compass over bubble level, each half the screen, zero-here for the level,
                    and the way out at the right-hand end of its row.

## THE DANGEROUS PARTS, AS THEY ARE NOW

- **The signing key was made in this sandbox, not on the phone** (a departure from
  `android-app.md` §3). Its only copy is the repository secret `TRAIL_KEYSTORE`, which cannot be
  read back out of GitHub. The app can be updated in place for as long as that secret exists. To
  follow §3 properly, make a PKCS12 on the phone and replace the secret **before v1 is installed**;
  after that, changing the key costs an uninstall.
- **Google's tiles are never cached and must never be.** `MapLayer.cacheable` is true only for
  the two layers we fetch ourselves, and `verify.py` fails the build if that definition changes.
- **The offline map file is held by a persisted SAF permission.** A reinstall can lose it; the app
  then says so in a sentence and offers the picker again rather than showing an empty grid.

## THE FILES

| File | What it holds |
|---|---|
| `Geo.kt` | haversine, bearings, Web Mercator tiles, how a coordinate is written. No Android. |
| `Track.kt` | `Fix`, the rules that admit a point, the live recording and its totals. No Android. |
| `Gpx.kt` | GPX 1.1, header, point, segment break, footer, file name. No Android. |
| `Level.kt` | pitch, roll, tilt, the bubble in its vial, the filter that wraps at north. No Android. |
| `Layers.kt` | the four maps, what each shows offline, the tile and WMS URLs. No Android. |
| `Trail.kt` | the live state both the screen and the service write to. |
| `Locator.kt` | the fused fix, and the satellite count read separately from GNSS status. |
| `Sensors.kt` | the rotation vector, true north from Android's own magnetic model, gravity. |
| `TrailService.kt` | the foreground recording, and the file written as the walk happens. |
| `MapCanvas.kt` | the mapsforge view, the four layers, the track line, the accuracy ring. |
| `Screens.kt` | the whole interface. |
| `Store.kt` | the four things remembered: layer, map file, export folder, level zero. |
| `scripts/verify.py` | the structural checks a compiler will not run. |

## BUILDING

    push to main   →   gates, Test 1, assembleRelease, release published with the APK

Test 1 may be run on a desk with kotlinc and a JUnit jar, because the five files above that say
"No Android" import nothing from it. That is a test of a mechanism, not a build.
