#!/usr/bin/env python3
"""verify.py: the checks that are cheap enough to run on every push and that a compiler will not
run for you. EVERY CHECK PRINTS WHAT IT EXAMINED (delivery-gate.md 14), because a check that finds
nothing and a check that runs nothing look identical from outside. Copied in shape from
COCKPIT_ANDROID/scripts/verify.py (14.9.2026)."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/mantra/trail"
TESTS = ROOT / "app/src/test/java/com/mantra/trail/CoreTest.kt"
TEST_FLOOR = 140

# The files Test 1 runs against on a desk. They may not reach for Android, or the mechanism can
# only be tested in an emulator and it stops being tested at all.
PURE = ["Geo.kt", "Track.kt", "Gpx.kt", "GpxRead.kt", "Level.kt", "Layers.kt", "Keys.kt", "Tracks.kt"]

failures, checks = [], []


def code_only(text):
    without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(l for l in without_block.split("\n") if not l.lstrip().startswith(("//", "*")))


def check(name, ok, detail):
    checks.append(name)
    print(f"{'pass' if ok else 'FAIL'}  {name}: {detail}")
    if not ok:
        failures.append(name)


# 1 the arithmetic imports nothing from Android
for name in PURE:
    src = code_only((MAIN / name).read_text())
    imports = re.findall(r"^import ", src, re.M)
    android = re.findall(r"^import (android\.|androidx\.).*$", src, re.M)
    check(f"{name} imports nothing from Android", not android,
          f"{len(imports)} imports examined, {len(android)} from android")

# 2 one version, derived everywhere
gp = (ROOT / "gradle.properties").read_text()
m = re.search(r"^appVersion=(\d+)$", gp, re.M)
bg = (ROOT / "app/build.gradle.kts").read_text()
check("one version in gradle.properties, derived in build.gradle.kts",
      bool(m) and "versionCode = appVersion" in bg and 'versionName = appVersion.toString()' in bg,
      f"appVersion={m.group(1) if m else '?'}")
for f in sorted(MAIN.glob("*.kt")):
    t = code_only(f.read_text())
    hard = re.findall(r"versionCode\s*=\s*\d+|versionName\s*=\s*\"\d", t)
    check(f"no hard-coded version in {f.name}", not hard, f"{len(hard)} found")

# 3 no key is in the repository, in any file, in any form (secrets.md 3)
tracked = [p for p in ROOT.rglob("*") if p.is_file()
           and ".git/" not in str(p) and "/build/" not in str(p) and p.suffix != ".jar"]
shapes = re.compile(r"(AIza|gsk_|ghp_|github_pat_|sk-ant-)[A-Za-z0-9_-]{20,}")
hits = [p.name for p in tracked if shapes.search(p.read_text(errors="ignore"))]
check("no key-shaped string anywhere in the tree", not hits,
      f"{len(tracked)} files examined, {len(hits)} hits {hits if hits else ''}")
# This check used to assert the opposite: that the manifest carried a ${googleMapsKey} placeholder
# filled from a repository secret. That is what put a live key inside a public APK on 14.9.2026.
# The rule reversed, so the check reversed with it rather than being deleted.
check("no key reaches the app at build time, by placeholder or otherwise",
      "googleMapsKey" not in (ROOT / "app/src/main/AndroidManifest.xml").read_text()
      and "googleMapsKey" not in bg,
      "neither the manifest nor the build file mentions one")

# 4 the manifest declares what a fix and a recording need
mf = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
for need in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "FOREGROUND_SERVICE_LOCATION",
             "POST_NOTIFICATIONS", "INTERNET", 'android:foregroundServiceType="location"',
             ".TrailService"):
    check(f"manifest carries {need}", need in mf, "present" if need in mf else "MISSING")
# Background location is a permission this app does not need and cannot justify: the recording
# runs in a foreground service, which is the lawful way to keep getting fixes with the screen off.
check("manifest does NOT ask for ACCESS_BACKGROUND_LOCATION",
      "ACCESS_BACKGROUND_LOCATION" not in mf, "absent")

# 5 Google's tiles are never cached, and the rule is in the code rather than in a comment
layers = code_only((MAIN / "Layers.kt").read_text())
canvas = code_only((MAIN / "MapCanvas.kt").read_text())
# This asked for layer.cacheable, which is about a LICENCE to keep tiles somebody else served.
# Tiles we rendered ourselves from a file on the phone are ours, and keeping them is what makes a
# revisited zoom instant. The one layer that may never be kept is Google, and that is the test.
check("the tile cache is kept on disk for every layer except Google",
      "layer.kind != LayerKind.GOOGLE_TILES," in canvas,
      "Google is the only false, and its terms are the reason")

# 6 the track is written as the walk happens, not assembled at the end
svc = code_only((MAIN / "TrailService.kt").read_text())
check("the GPX header is written when recording starts", "Gpx.header(" in svc, "present")
check("every accepted point is appended and flushed",
      "Gpx.point(" in svc and svc.count("flush()") >= 2,
      f"point written, flush called {svc.count('flush()')}x")
check("the footer is written when recording stops", "Gpx.footer()" in svc, "present")
check("the notification is taken down when the service dies",
      "cancel(NOTIFICATION_ID)" in svc, "present")

# 7 nothing on the screen appears or disappears: the controls are always drawn and are enabled
# or not (design-language.md 1). Five keys, five enabled arguments.
screens = code_only((MAIN / "Screens.kt").read_text())
keys = re.findall(r"\bKey\(", screens)
check("the control row draws every key unconditionally",
      len(keys) + screens.count("MarkKey(") + screens.count("RecordKey(") >= 6,
      f"{len(keys)} glyph keys plus the two marks: no key is conditional on state")
# A key that cannot act SAYS WHY. v6 dropped the disabled look from the map screen: a dimmed
# button with no explanation is the same dead end as a missing one.
check("a layer that cannot draw says why instead of going grey",
      "Trail.say(Layers.missingKey(layer))" in screens or "Layers.missingKey(layer)" in screens,
      "the reason names the key and where to put it")
check("the way out of the settings face is at the right-hand end of its top row",
      "clickable(onClick = onClose)" in screens and screens.index("onClose") > 0,
      "the ✕ in the corner it occupies on every face here")

# 8 the test floor ratchets
tests = TESTS.read_text()
n = len(re.findall(r"@Test", tests))
check(f"at least {TEST_FLOOR} unit tests", n >= TEST_FLOOR, f"{n} @Test cases")

# 9 the APK is built by CI and by nothing else (android-app.md 1a)
wf = (ROOT / ".github/workflows/build-apk.yml").read_text()
check("a workflow exists and builds the release APK",
      "assembleRelease" in wf, "present")
check("the workflow publishes a release, so the build can be downloaded",
      "gh release create" in wf, "present")
check("no committed local.properties could point a build at a desk's SDK",
      not (ROOT / "local.properties").exists() or "local.properties" in (ROOT / ".gitignore").read_text(),
      "ignored")


# NOTHING OF OURS UNDER THE SYSTEM BARS, AND TWO VIEWS ONLY (design-language.md, written
# 14.9.2026 from the v4 screenshot). These are the checks that would have caught it, and they
# exist because nothing on a desk has a status bar to be covered by.
pads = screens.count("safeDrawingPadding()")
activity = (MAIN / "MainActivity.kt").read_text()
check("every overlay of the map sits inside the safe area", pads >= 3,
      f"{pads} safeDrawingPadding calls: the top line, the controls, the settings face. "
      "The bare view has no overlay at all, which is why three is the number.")
check("the window is told we draw edge to edge ourselves",
      "setDecorFitsSystemWindows(window, false)" in activity, "present")
check("full screen hides the system bars, and coming back shows them",
      "hide(WindowInsetsCompat.Type.systemBars())" in activity and
      "show(WindowInsetsCompat.Type.systemBars())" in activity, "both directions present")
# v5 clipped because each key carried a word under its glyph. v6 keys are a glyph alone, so five
# fit where three did; the ceiling is on WORDS in a row, not on keys.
key_body = screens.split("private fun RowScope.Key")[1].split("\n}\n")[0]
check("no key carries a word under its glyph any more",
      screens.count("private fun RowScope.Key") == 1 and key_body.count("Label(") == 1,
      f"{key_body.count('Label(')} Label call in the key body: the glyph, and nothing under it")
# Seven bare glyphs at a 4 dp gap is 48 px each on a 390 px phone, which is still a thumb. The
# ceiling rose because the keys lost their words, not because the phone got wider.
control_row = screens.split("horizontalArrangement = Arrangement.spacedBy(4.dp),")[1].split("\n                }")[0]
row_keys = len(re.findall(r"\b(?:Record|Mark)?Key\(", control_row))
check("the control row holds at most seven keys", row_keys <= 7,
      f"{row_keys} keys: seven bare glyphs across a 390 px phone is 48 px each")

# CH caches what is on the view, and never Google's tiles


# THE OFFLINE MAP IS FETCHED BY THE APP, WITH THE SIZE SAID FIRST AND THE PROGRESS SHOWN
# (download-monitor.md: nothing longer than a minute happens in the dark).
download = (MAIN / "MapDownload.kt").read_text()
check("the download resumes rather than starting again",
      "Range" in download and ".part" in download, "a Range header and a part file")
check("a part file only becomes the map when it is whole",
      "part.length() < total" in download and "renameTo(finished)" in download,
      "the length is checked before the rename")
check("the size is on the screen before the download starts",
      "OfflineDownload.LABEL" in screens or "OfflineDownload.LABEL" in activity, "present")


# NO KEY IS BUILT INTO THIS APP (14.9.2026, after a live Maps key went out inside a public APK).
# The picker is the only way one arrives, and these are the checks that keep it that way.
gradle_kts = (ROOT / "app/build.gradle.kts").read_text()
check("the build takes no service key",
      "googleMapsKey" not in gradle_kts and "HAS_GOOGLE_KEY" not in gradle_kts,
      "no placeholder, no BuildConfig field")
check("the manifest holds no key of any kind",
      "API_KEY" not in mf and "${" not in mf.split("<application")[1],
      "no meta-data key, no placeholder")
sdk_lines = [l for l in gradle_kts.splitlines()
             if ("play-services-maps" in l or "maps-compose" in l) and "implementation" in l]
check("the Google Maps SDK is gone, because it can only read a key from the installed app",
      not sdk_lines, f"{len(sdk_lines)} dependency lines on it")
keys_src = (MAIN / "Keys.kt").read_text()
check("keys are sorted by shape, not by asking him which is which",
      "fun providerOf" in keys_src, "one function decides the service from the shape")
check("a key is never written to the screen, only its service",
      "keyState" in (MAIN / "Store.kt").read_text() and "it.key" not in screens,
      "the settings row names services, never values")
workflow = (ROOT / ".github/workflows/build-apk.yml").read_text()
check("the workflow uses no key secret",
      "GOOGLE_MAPS_API_KEY" not in workflow,
      "the only secrets are the signing keystore and its password")


# CH is gone (15.9.2026). Pre-fetching a region is bulk downloading, which Thunderforest allow
# only on their Small Business plan and above, and on the offline map it cached a file we already
# have. What remains is ordinary caching of tiles actually looked at, which no button controls.
check("no button pre-fetches anybody's tiles",
      'glyph = "CH"' not in screens and not (MAIN / "Caching.kt").exists(),
      "the key and the arithmetic behind it are both gone")
check("the credits are gathered in settings, not printed over the map",
      "creditOnMap: Boolean get() = false" in layers and "map credits" in screens,
      "one block at the bottom of the settings face")

# WHAT THE PHONE SHOWED ON 15.9.2026, TURNED INTO CHECKS.
# This check said "no filled surface over the map" until the phone showed that a shadow alone is
# not readable on a pale street map. A bar the height of its line is not a box: the rule it keeps
# is that nothing takes map it does not need.
# A bar belongs to a LINE, never to the column that holds it: on the column it also covers the
# safe-area inset and every empty row inside, which is how a strip of text shaded half the map.
map_screen = screens.split("private fun SettingsFace")[0]
check("no bar is painted on a column",
      ".background(Paint.Bar)" not in map_screen.split("private fun Panel")[0] or
      "Column(\n                Modifier.fillMaxWidth().align(Alignment.TopCenter).safeDrawingPadding()" in map_screen,
      "the background belongs to the line and to the key row, not to their container")
check("an empty line takes no height at all",
      "if (note != null) NoteLine(note)" in screens and "if (recording) TrackLine(stats)" in screens,
      "drawn only when there is something in them, rather than at zero opacity")
# Reversed on 15.9.2026 after reading Thunderforest's terms: the attribution may not be removed
# from an app. One dim line, on the fetched layers only, guarded by creditOnMap.
check("no credit is printed over the map",
      "attribution" not in map_screen, "the map screen carries none of them")

# The map drew halfway because mapsforge renders a SQUARE frame buffer by default: two and a half
# screens of tiles on a tall phone, thrown away at every zoom.
check("the frame buffer is the shape of the screen, not a square",
      "Parameters.SQUARE_FRAME_BUFFER = false" in (MAIN / "MapCanvas.kt").read_text(),
      "the square buffer is for rotation, and this map does not rotate")
check("rendered tiles are kept on disk for every layer but Google",
      "layer.kind != LayerKind.GOOGLE_TILES," in (MAIN / "MapCanvas.kt").read_text(),
      "a zoom visited once comes back instantly")
check("every word over the map carries a shadow instead",
      "Shadow(color = Paint.Ground" in screens, "one Label, one shadow, no panel")
# Split in two on 15.9.2026: the crosshair over the map and the mark on the key are different
# things, and only the key's mark is a position colour.
check("the crosshair over the map is four black hairlines and nothing else",
      "Color(0x80000000)" in screens.split("private fun CentreCross")[1][:600]
      and "drawCircle" not in screens.split("private fun CentreCross")[1].split("\n}")[0],
      "half transparent, middle empty")
check("the key's mark is unchanged and still the position colour",
      "Paint.AmberBright" in screens.split("private fun PositionMark")[1][:400],
      "ring and dot, ringed in near-black")
check("the settings face scrolls",
      "verticalScroll(rememberScrollState())" in screens,
      "so the last row is reachable however many rows there are")
row = control_row
order = [k for k in ["\u2212", "MarkKey", "RecordKey", "layer.short", "\u2699", '"+"'] if k in row]
# Six keys with CH gone, so the red circle is third of six: as near the middle as an even row
# allows, and still the one under the thumb that reaches the phone's home button.
check("the record circle sits at the middle of the row",
      abs(order.index("RecordKey") - (len(order) - 1) / 2) <= 0.5,
      f"{len(order)} keys, red one at position {order.index('RecordKey') + 1}")


# THE OFFLINE MAP WENT BLANK ON THE WAY IN (15.9.2026). Three things could do that and all three
# are now closed; the checks keep them closed.
canvas_src = (MAIN / "MapCanvas.kt").read_text()
# The rule grew: it is not only the vector map that may be enlarged past its data. Every layer now
# carries two ceilings — where its tiles stop, and how far the view may go while mapsforge scales
# the last real tile (Baba, 15.9.2026: "OpenStreetMap goes to zoom level 18 and it stops. Why?").
check("the view is never clamped to where the tiles stop",
      "layer.viewMaxZoom.toByte()" in canvas_src and "val viewMaxZoom" in layers,
      "two ceilings: the service's tiles, and the view over them")
check("the tile cache holds more than one screenful",
      '"tiles-${layer.id}",' in canvas_src and "2f," in canvas_src,
      "two screenfuls, so there is room for the level being rendered into")
check("the app asks for the large heap a country file at street zoom needs",
      'android:largeHeap="true"' in mf, "present")
check("the zoom is on the screen, so a fault can be reported with a number",
      'Label("z$zoom"' in screens, "present on the top line, beside the map's name")


# THE RACE THAT KEPT THE MAP BLANK FROM v6 TO v10. The first draw ran from an effect that fires
# before AndroidView builds its view, so it found no canvas and returned at its first line —
# silently. Two checks, because either half alone would let it back in.
check("the first draw is triggered by the view existing, not by a bare effect",
      "onReady()" in screens and "LaunchedEffect(ready)" in screens,
      "the factory says when the view is real")
check("no path out of showLayer is silent",
      "The map view is not up yet" in screens,
      "the missing canvas now says so")
check("a layer that cannot draw falls back to one that can",
      "showing OpenStreetMap meanwhile" in screens,
      "the screen is never white without a sentence on it")
check("zoom is on the screen as keys, not only as a pinch",
      "zoomOut()" in screens and "zoomIn()" in screens, "minus and plus at both ends of the row")


# THE MAP CAME IN AS A BAND WITH WHITE ABOVE IT: tiles rendered from the offline file were thrown
# away as soon as they left the screen, so mapsforge had no parent tile to scale while the new
# ones rendered, and there was nothing to show.
check("tiles rendered from the offline file are kept",
      "layer.kind != LayerKind.GOOGLE_TILES," in canvas_src,
      "the persistent cache covers our own rendering too; Google is the only exception")


# z19 WAS WHITE (15.9.2026). mapsforge draws the PARENT tile scaled while a tile renders, but it
# looks for that parent with getImmediately(), which only reads the in-memory half of the cache.
# Two settings decide whether the parent is still there.
# Replaced on 15.9.2026 after the desk reproduction: mapsforge renders this file at z19 to z21
# perfectly, so the blank above 18 was the cache, and a guessed ratio is what made it too small.
# mapsforge's own docs call the ratio an approximation made before the view has a size.
check("the tile cache is sized from real pixels, not from a guessed ratio",
      "metrics.widthPixels" in canvas_src and "metrics.heightPixels" in canvas_src,
      "the screen's own dimensions decide how many tiles a frame needs")
check("the cache has room for the zoom being entered as well as the one being left",
      "overdrawFactor * 2.0" in canvas_src, "twice the frame")
check("the frame buffer is not square, because this map does not rotate",
      "Parameters.SQUARE_FRAME_BUFFER = false" in canvas_src,
      "a square buffer renders two and a half screens for every one you look at")
check("an empty map says so by asking the file, not by waiting to be photographed",
      "fun emptyHere" in canvas_src and "emptyHere()" in screens,
      "the read the renderer is about to do anyway")


# THE SILENCE COMPLAINT, 15.9.2026: "I'm waiting for it to download. Since I don't have indicator,
# I don't know what's going on." Long work in silence is the failure mode this whole app keeps
# repeating, so the network has a line of its own.
net_src = (MAIN / "Net.kt").read_text()
check("the speed is measured by the phone, not reported by the thing being measured",
      "TrafficStats.getUidRxBytes" in net_src,
      "so mapsforge's own tile fetching is counted too")
check("a negative counter never becomes a negative speed",
      "rx < 0 || tx < 0" in net_src, "UNSUPPORTED is -1 on some devices")
check("the status line is on the map screen",
      "StatusLine(net)" in screens, "present above the note line")
check("the map key skips what cannot draw",
      "fun nextUsable" in screens, "a press that does nothing is not a press")
check("a blank offline map explains itself at the zoom it goes blank",
      "emptyHere()" in screens, "the file is asked, not the user")


# The map's name shares the top line with the coordinates (15.9.2026), so the line must not clip.
check("the map name is on the top line",
      "Label(layer.name, Paint.Amber" in screens,
      "next to the zoom, where the key beside it says which family it is")
check("the scale bar is gone", "mapScaleBar.isVisible = false" in canvas_src,
      "the zoom number says the same thing in five characters")


# The reproduction that ended five versions of guessing is kept in the repository, because the
# next person to see a blank map should run it before touching the app (four-tests.md, Test 1:
# attack the mechanism where it is cheap to attack).
probe = ROOT / "tools/RenderProbe.java"
check("the desk reproduction is kept", probe.exists() and "executeJob" in probe.read_text(),
      "renders the real file at every zoom and counts ways, points and colours")


# THE TRACK, AFTER THE WALK (15.9.2026): a popup that names it, a folder he can see the name of,
# and a manager that renames and deletes.
tracks_src = (MAIN / "Tracks.kt").read_text()
check("cancelling after a walk still saves it",
      "onCancel = {\n                    Trail.dealtWith()" in screens,
      "cancel means do not rename, never throw the walk away")
check("renaming never writes over another walk",
      "already exists" in tracks_src, "a name collision refuses rather than overwrites")
# Export became one action on 15.9.2026: Android's own save dialog asks where and what to call it,
# and the track on the phone takes that name afterwards. Rename in the manager is gone with it.
# Export went out with the server (15.9.2026): a finished walk is written straight into the folder
# he chose, so there is nowhere left to export it TO. The menu is that folder, filtered to GPX.
folder_src = (MAIN / "Folder.kt").read_text()
check("the tracks menu is the chosen folder, filtered to GPX",
      'endsWith(".gpx", ignoreCase = true)' in folder_src and "fun list" in folder_src,
      "not a private copy nobody can find")
check("the folder's name is on the menu",
      'SettingRow("folder", folder, onChooseFolder)' in screens,
      "a list of files nobody can find is a list")
check("renaming keeps the extension and shows it separately",
      "Tracks.safeFileName(newName)" in folder_src and 'Label(".${track.extension}"' in screens,
      "he renames a name; the disk keeps a file")
check("there is no export left anywhere",
      "CreateDocument" not in activity and "onExport" not in screens,
      "the walk is already where he will look for it")
check("deleting a track asks twice",
      "sure? delete" in screens, "one thumb on a hillside is not a decision")
check("the settings row names the folder rather than saying chosen",
      "store.exportFolderName" in screens, "Documents/Tracks, not the word chosen")
check("the saved message says where it went",
      'Saved to ${Folder.label(this@MainActivity, store)}' in activity,
      "the folder is named, so the message can be checked rather than trusted")
# Both faults of 15.9.2026: the copy ran on the main thread, and the answer went to a line that
# is behind the manager whenever the manager is what he is looking at.
check("an export happens off the main thread and says so where he is looking",
      "withContext(Dispatchers.IO)" in activity and "Trail.sayInManager" in activity,
      "the track manager carries its own line now")
check("the track list is loaded rather than read during composition",
      "loaded = withContext(Dispatchers.IO) { tracks() }" in screens,
      "this is why changing the line colour was slow")
check("the tools are a window of their own, not a settings row",
      "private fun ToolsFace" in screens and 'glyph = "T"' in screens,
      "one key away, and they cover the map while they are open")


# WHAT HE ASKED FOR ON 15.9.2026, AFTER THE MAP SERVER LANDED.
# Rebuilt from the ground up on 15.9.2026: the box waits, it is empty, and it has two words on it.
check("the name box has no clock in it",
      "secondsLeft" not in screens and "touched" not in screens,
      "it waits; there is nothing to decide about whether he has started typing")
check("the name box starts empty and says what the name is now",
      'mutableStateOf("")' in screens.split("private fun NameBox")[1][:400],
      "he is typing a new name, not correcting an old one")
check("the entry box has a frame and the cursor is already in it",
      "border(1.5.dp, Paint.Amber" in screens and "focus.requestFocus()" in screens,
      "on a dark panel an unfocused dark field is a label, not a box")
check("the name box has two answers and they are named OK and cancel",
      '"cancel"' in screens and '"OK"' in screens,
      "no third thing to read on a hillside")
# The reason it was not empty: the file name was being built from a date stamp AND a name that
# was already a date, so nothing matched the pattern and the box opened full of numbers.
check("a track file is named after the track and nothing else",
      "Tracks.safeFileName(name)" in (MAIN / "TrailService.kt").read_text()
      and "fun fileName" not in (MAIN / "Gpx.kt").read_text(),
      "the builder that stamped a second date is gone, not merely unused")
# The rename in the manager did nothing and said nothing: DocumentFile.fromSingleUri returns a
# SingleDocumentFile, which does not implement renameTo at all.
check("renaming goes to the provider, not through a wrapper that cannot do it",
      "DocumentsContract.renameDocument" in folder_src, "the call that works on a tree's document")
check("deleting goes the same way",
      "DocumentsContract.deleteDocument" in folder_src, "one lesson, applied twice")
check("the position can be locked to the middle of the screen",
      "if (follow && fix != null) CanvasHolder.canvas?.centreOn(fix)" in screens,
      "one press holds it, the next lets the map go")
# Refined 15.9.2026: one tap centres, TWO IN A ROW lock. A second tap a minute later is somebody
# centring again, not somebody asking for a lock.
check("two taps in a row are what lock it",
      "now - lastCentreTap < 1_000L" in screens, "a second inside a second")
check("a map can be taken out of the toggle and still be in the list",
      "store.inToggle" in screens and "fun inToggle" in (MAIN / "Store.kt").read_text(),
      "excluding a map from the toggle is not the same as not having it")
check("the toggle skips what he took out of it",
      "if (!store.inToggle(candidate.id)) return@repeat" in screens, "in the same place it skips what has no key")
check("the crosshair is hairlines", "val hair = 1.dp.toPx()" in screens, "one pixel, four of them")
check("choosing a map closes the settings and shows it",
      "settings = false\n                    scope.launch { showLayer(store, picked) }" in screens,
      "one decision, not two")
check("the map families fold, and the fold is remembered",
      "store.collapsed(key)" in screens and "store.setCollapsed(key, folded)" in screens,
      "between sessions, so nothing has to be folded away twice")
check("a saved walk can be drawn on the map in a chosen colour",
      "showSavedTrack" in canvas_src and "TRACK_COLOURS" in screens,
      "five colours, and the shown line is separate from the recording line")
# The server came out of this app on 15.9.2026: the offline files work, so a second app in the
# path was one more thing to be running. MANTRA_MAP_SERVER still exists on its own.
check("no part of the map server is left in this app",
      "ServerStatus" not in screens and "ServerStatus" not in activity
      and not (MAIN / "ServerStatus.kt").exists() and "SERVER" not in layers,
      "the layer, the family, the status row and the file are all gone")

print(f"\n{len(checks)} checks, {len(failures)} failed")
if failures:
    print("failed: " + ", ".join(failures))
    sys.exit(1)
