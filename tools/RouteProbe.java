import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * BROUTER, ON A DESK, BEFORE IT GOES ANYWHERE NEAR THE PHONE.
 *
 * The same engine sources, the same profile and the same segment file that the app would carry,
 * routing between two real points near Zagreb. What has to be proved here is not that the library
 * exists but that it ANSWERS: a track with points in it, a length, a climb, and more than one
 * answer when more than one is asked for.
 *
 * If this is silent or empty on a desk, nothing about putting it in an APK would have made it
 * work, and the day it was needed would have been on a hillside.
 */
public class RouteProbe {

    public static void main(String[] args) throws Exception {
        File segments = new File("/tmp/brtest/segments");
        File profile = new File("/tmp/brtest/profiles/trekking.brf");

        // Medvednica: from the Sljeme road up towards the summit, a walk with real climb in it.
        double fromLat = 45.8983, fromLon = 15.9506;
        double toLat = 45.9111, toLon = 15.9689;

        for (int alternative = 0; alternative < 3; alternative++) {
            RoutingContext rc = new RoutingContext();
            rc.localFunction = profile.getAbsolutePath();
            rc.setAlternativeIdx(alternative);

            List<OsmNodeNamed> waypoints = new ArrayList<>();
            waypoints.add(waypoint("from", fromLat, fromLon));
            waypoints.add(waypoint("to", toLat, toLon));

            long started = System.currentTimeMillis();
            RoutingEngine engine = new RoutingEngine(null, null, segments, waypoints, rc);
            engine.doRun(30_000);
            long took = System.currentTimeMillis() - started;

            if (engine.getErrorMessage() != null) {
                System.out.println("alternative " + alternative + ": " + engine.getErrorMessage());
                continue;
            }
            OsmTrack track = engine.getFoundTrack();
            if (track == null) {
                System.out.println("alternative " + alternative + ": no track and no error");
                continue;
            }
            System.out.printf(
                    "alternative %d | %d points | %d m | climb %d m | %d ms%n",
                    alternative, track.nodes.size(), track.distance, (int) track.ascend, took);
            if (alternative == 0) {
                System.out.println("  first three points:");
                for (int i = 0; i < Math.min(3, track.nodes.size()); i++) {
                    System.out.printf(
                            "    %.6f, %.6f%n",
                            track.nodes.get(i).getILat() / 1000000.0 - 90.0,
                            track.nodes.get(i).getILon() / 1000000.0 - 180.0);
                }
            }
        }
    }

    private static OsmNodeNamed waypoint(String name, double lat, double lon) {
        OsmNodeNamed node = new OsmNodeNamed();
        node.name = name;
        node.ilat = (int) ((lat + 90.0) * 1000000.0 + 0.5);
        node.ilon = (int) ((lon + 180.0) * 1000000.0 + 0.5);
        return node;
    }
}
