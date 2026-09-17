# WHAT IS LEFT

Kept because he asked, on 17.9.2026, to be told after every build what remains. Anything that is
done leaves this file; anything he refuses leaves it with a line saying so.

## Next, in the order I would do them

1. **Elevation profile** — a height graph under a found route and under a saved track. Google's
   Elevation API answered on a desk (975, 980, 954, 951, 868 m along the Sljeme line) and BRouter
   already returns the climb, so an offline profile is possible from the route's own points.
   One billed request per route when Google is the source.
2. **Street View at a point** — one photograph at A or B, to see a trailhead before driving to it.
   One billed image per press.
3. **Turn instructions** — Google's Routes answer already carries them; they are fetched and
   thrown away. Useful on a road, useless on a path, so they would be shown only for a Google route.
4. **The map screen's state**, rewritten the way the settings were — it is the last big file where
   state is read during drawing, which is what made the settings slow and the ticks dead.

## Known, not yet decided

- **Attribution must return to the map** if the app ever leaves his phone: Thunderforest's terms
  wanted it and they are gone, but OpenStreetMap's ODbL and Google's terms both still ask.
- **The signing keys** for both apps were made in the sandbox, not on the phone, and exist only as
  repository secrets (android-app.md 3 wants a copy on the phone so he can rotate them).
- **Nothing is tested on a phone from this side.** Every screen bug this week was found by his
  screenshots, not by the checks.

## Refused, and why

- **Aerial View, Maps SDK, 3D tiles** — a second renderer fighting VTM, and none of it works
  without a signal, which is the case the app exists for.
