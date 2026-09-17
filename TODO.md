# WHAT IS LEFT

Kept because he asked, on 17.9.2026, to be told after every build what remains. Anything that is
done leaves this file; anything he refuses leaves it with a line saying so.

## Next, in the order I would do them

He walks half in signal and half out of it (17.9.2026), so the signal half should be worth having
and the other half must never depend on it.

0. **Whether Google's tiles now draw** (v63's https engine) — still unseen from this side.

1. **The height graph for a SAVED TRACK** — v64 put it under a found route; a walk already
   recorded should be able to show the same thing.
2. **Street View at a point** — one photograph at A or B, to see a trailhead before driving to it.
   One billed image per press.
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
