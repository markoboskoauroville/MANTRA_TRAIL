# WHAT IS LEFT

Kept because he asked, on 17.9.2026, to be told after every build what remains.

## The one that matters now

**The key row speaks only to VTM.** Google's renderer draws (v69, proved on his phone), but the
minus, plus, centre, crosshair and point keys all call CanvasHolder, which holds the OFFLINE
canvas — twenty-four places in the screen, against three that know the Google one exists. On a
Google map they do nothing and say "the map view is not up yet", which is the line he is seeing in
both screenshots. Every one of those keys needs to ask whichever canvas is up.

## Then, in order

1. **His position on the Google map.** Google's blue dot needs the location permission handed to
   the SDK; the app has the permission but never passes it, so the dot is missing.
2. **The recorded walk and the found route on the Google canvas.** The lettered points and the
   straight line between them are drawn; a recording in progress and a saved track are not.
3. **The compass over the Google map** — it reads VTM's rotation, so it will not turn with a map
   Google is drawing.
4. **Google's logo sits under the key row.** Their terms require it stay visible: the row needs to
   leave the bottom-left corner alone, or the map needs padding under it.
5. **Height graph for a saved track** — it exists for a found route only.
6. **Street View at a point** — one photograph at A or B before driving to a trailhead.
7. **The map screen's state**, rewritten the way the settings were. It is the last big file where
   state is read while drawing, which is what made the settings slow and the ticks dead.

## Open, not decided

- **Attribution on the map** if this app ever leaves his phone: OpenStreetMap's ODbL and Google's
  terms both ask for it, and it lives in the settings now.
- **The signing keys** for both apps exist only as repository secrets, not on his phone
  (android-app.md 3 wants a copy he can rotate).
- **Two keys now.** The restricted one is in the APK and works only there; the unrestricted test
  one is in the sandbox vault. A budget alert in Cloud Console would be wise for the second.

## Refused, and why

- **Aerial View, Maps 3D SDK** — none of it works without a signal, which is the case this app
  exists for.
