# Delivery record — v1

    artefact   1-mantra-trail-v1.apk
    built      GitHub Actions, from the commit the release names. Never on a desk.

## MEASURED

    Test 1            87 cases, 0 failures, 0.107 s (kotlinc + JUnit, no Android SDK)
    harness proved    5 cases were red first and all 5 were wrong expectations in the test
    TK25 WMS          one real GetMap in EPSG:3857: 200, image/png, 65,487 bytes,
                      33,562 opaque pixels, 1,933 distinct colours — real map, not a blank tile
    mapsforge         MapCanvas.kt typechecked against the real 0.25.0 jars and android.jar
    icon              painted extent 40 of the 72 unit mask = 0.556, inside the 0.55–0.60 band
    gates             G1 provenance, G2 history and artefact, G3 verify.py, G5 loops — in CI

## NOT TESTED

Said as plainly as what works (four-tests.md §4).

- **Nothing has run on a phone.** No fix has been taken, no track recorded, no GPX opened in
  another app, no tile drawn on a screen. Everything below the arithmetic is unproven.
- **Test 2 does not exist yet.** The one real call made was to the TK25 service with curl, not
  from the app.
- **Test 3 does not exist yet.** No sabotage: no revoked folder permission, no deleted map file,
  no network pulled mid-fetch, no recording carried through a reboot.
- **Test 4 cannot exist yet.** There is no previous version to upgrade from. It becomes real at v2
  and it is the one that matters most, because the cost of failing it is lost tracks.
- **The Google layer has never been displayed.** The key is in a repository secret and reaches
  the build; whether the SDK accepts it, and whether the key's restrictions allow this package
  name, is unknown until the app is opened.
- **The launcher icon's ratio was computed from the path geometry, not rendered and measured.**
  `app-icon.md` §3 asks for a render. Do that at v2.
- **The signing key was made in a sandbox, not on the phone** (`android-app.md` §3). Its only copy
  is the repository secret. See HANDOFF.md for the cost of changing it later.
