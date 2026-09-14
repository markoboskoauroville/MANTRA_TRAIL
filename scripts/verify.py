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
TEST_FLOOR = 100

# The files Test 1 runs against on a desk. They may not reach for Android, or the mechanism can
# only be tested in an emulator and it stops being tested at all.
PURE = ["Geo.kt", "Track.kt", "Gpx.kt", "Level.kt", "Layers.kt", "Caching.kt", "Keys.kt"]

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
check("the Google key arrives as a placeholder, never as a literal",
      "${googleMapsKey}" in (ROOT / "app/src/main/AndroidManifest.xml").read_text()
      and "googleMapsKey" in bg,
      "manifest placeholder and gradle property both present")

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
check("cacheable is true only for the layers we fetch ourselves",
      "kind == LayerKind.RASTER_XYZ || kind == LayerKind.WMS" in layers,
      "the definition names the two kinds")
canvas = code_only((MAIN / "MapCanvas.kt").read_text())
check("the tile cache is persistent only when the layer allows it",
      "layer.cacheable," in canvas,
      "the cache is created with the layer's own answer, not with true")

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
enabled = re.findall(r"enabled = ", screens)
check("the control row draws all five keys unconditionally",
      len(keys) >= 5, f"{len(keys)} Key( calls")
check("the keys are governed by enabled, not by being drawn or not",
      len(enabled) >= 5, f"{len(enabled)} enabled arguments")
check("the way out of the tools face is a close control in its own row",
      "onClose" in screens and 'ViewKey(glyph = "\u2715", onClick = onClose)' in screens,
      "the same ✕ key the full-screen view uses, in the same corner")

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
check("every overlay of the map sits inside the safe area", pads >= 4,
      f"{pads} safeDrawingPadding calls: top strip, controls, tools face, full-screen key")
check("the window is told we draw edge to edge ourselves",
      "setDecorFitsSystemWindows(window, false)" in activity, "present")
check("full screen hides the system bars, and coming back shows them",
      "hide(WindowInsetsCompat.Type.systemBars())" in activity and
      "show(WindowInsetsCompat.Type.systemBars())" in activity, "both directions present")
check("no row of keys holds more than three",
      max((len(re.findall(r"\bKey\(", chunk)) for chunk in screens.split("Row(")), default=0) <= 3,
      "three across a 390 px phone is the ceiling; six clips the labels")

# CH caches what is on the view, and never Google's tiles
check("CH refuses Google with a sentence rather than a dead button",
      "Caching.refusal(layer)" in screens and "Google" in (MAIN / "Caching.kt").read_text(),
      "the refusal is shown, not swallowed")
check("the cache run says what did not come", "did not come" in screens,
      "failed tiles are counted on the screen, never hidden")

print(f"\n{len(checks)} checks, {len(failures)} failed")
if failures:
    print("failed: " + ", ".join(failures))
    sys.exit(1)
