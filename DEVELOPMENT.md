# Development

Past tense. Every decision and the reason it was made, appended, never rewritten.

## 14.9.2026 — v1, the first build

**The name.** Baba asked for a clone of Outdooractive and first called the app that. Outdooractive
is a live product from a German company and the repository is public, so the app was named Mantra
Trail instead.

**Google cannot be cached, and that decided the shape of the app.** The first sketch had Google as
the main map with a cache behind it. Google's Map Tiles API policies forbid pre-fetching, indexing,
storing or caching content and name offline use as a prohibited case; the Maps SDK for Android
carries the same restriction with place IDs as the only exemption. So Google became one layer of
four, online only, labelled as such — and the offline work went to maps whose licences allow it.

**TK25 was proved before it was designed around.** The state survey's WMS was asked for a single
256 px tile in EPSG:3857 over Velebit: HTTP 200, `image/png`, 65,487 bytes, 33,562 opaque pixels
and 1,933 distinct colours. A 200 with a blank tile would have looked identical from the status
code alone, which is why the colours were counted (`silent-failure.md`: check the outcome, not the
operation).

**GPX 1.1 over FIT, TCX and KML**, because everything reads it and the schema is published. It is
written point by point rather than assembled at the end: a file assembled at the end does not exist
when the battery dies on the ridge.

**Accuracy is the state, so it carries the colour.** Sand under 10 m, amber to 50 m, red past it,
dim when the phone will not say. The ring on the map is drawn to scale in metres, because a number
in a corner is read as a score and a circle is read as the ground the fix cannot tell apart.

**The ascent threshold is 3 m.** Without it a phone lying still on a table records a climb, because
its altitude wanders by several metres a minute. Test 1 asserts exactly that case.

**Pitch and roll use atan2 against the other two axes, not asin.** The asin version is correct up
to 45 degrees and wrong past it, which is the worst way to be wrong: it looks right in every casual
check. Tilt composes the two exactly, so 3 degrees one way and 4 the other is 5 off level, not 7.

**The compass filter wraps.** Averaging 359 and 1 the ordinary way gives 180 and points the needle
exactly backwards, at the moment somebody is watching it. `Level.smoothAngle` goes the short way
round and Test 1 closes it.

**Five expectations in Test 1 were wrong before any code was.** A tile number, an epoch in
milliseconds and a tag count where `<trk` was also matching `trkseg` and `trkpt`. All five were
invented rather than measured; all five were measured and corrected. The harness has been seen to
fail, so it is not a rumour.

**mapsforge's API was read from its own sources rather than guessed**, and `MapCanvas.kt` was
typechecked against the real jars and `android.jar` with kotlinc before anything was pushed. That
is not a local build — it produces no artefact — and it removed several CI rounds.

**A local Gradle build was attempted and abandoned.** The sandbox had a JRE and no compiler, and
the attempt was the wrong instinct: Baba's answer was to write the rule into the manifest instead.
`android-app.md` §1a now says the APK is built by GitHub Actions and never on a desk, with the
three reasons — provenance, a reproducible environment, and the one signing key.

## 14.9.2026 — v1 build 1 was red, and the check that should have caught it was mine

`InternalRenderTheme` does not exist in mapsforge 0.25.0; the themes are
`org.mapsforge.map.rendertheme.internal.MapsforgeThemes`. Two lines, and CI found them in six
minutes.

**It should not have reached CI.** The file had been typechecked locally against the real jars and
reported clean — because the filter was `grep -E "^(error|warning)"` and this kotlinc writes
`Broken.kt:1:10: error: unresolved reference`, with the word in the middle of the line and not at
the start of it. **A check that cannot fail looks exactly like a check that passes**
(`checking-the-checks.md`). The filter was replaced with the compiler's own exit status, and then
proved by breaking `Geo.normaliseDeg` on purpose: exit 1 with 51 errors broken, exit 0 restored.

The restore is worth its own line. The first attempt to put the deliberately broken file back ran
after a `return` in a shell function and never executed, so the next run reported 51 errors that
were all my own sabotage. **When you sabotage something on purpose, confirm the repair before
reading the next result** (four-tests.md Test 3: exclude the error you caused yourself).

## 14.9.2026 — v2 was red on a dependency declared twice

`mapsforge-map-android` already depends on `com.caverock:androidsvg` as a plain jar. Declaring
`androidsvg-aar` alongside it put both on the path, and `checkReleaseDuplicateClasses` counted
every class in the library twice. The line came out: the library is still there, brought by the
module that needs it, which is also the module that will keep choosing the right version.
