package com.airtak.routing;

import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class BrouterEngine {

    private BrouterEngine() {}

    public static BrouterResult calculateRoute(
            File segmentDir,
            File profileDir,
            String profileName,
            double fromLat, double fromLon,
            double toLat, double toLon,
            long timeoutMs
    ) {
        RoutingContext rc = new RoutingContext();
        rc.localFunction = new File(profileDir, profileName + ".brf").getAbsolutePath();

        ArrayList<OsmNodeNamed> waypoints = new ArrayList<>();
        waypoints.add(createWaypoint(fromLat, fromLon, "start"));
        waypoints.add(createWaypoint(toLat, toLon, "end"));

        RoutingEngine engine;
        try {
            engine = new RoutingEngine(null, null, segmentDir, waypoints, rc);
        } catch (Exception e) {
            return BrouterResult.failure("Engine init failed: " + e.getMessage());
        }

        engine.quite = true;
        try {
            engine.doRun(timeoutMs);
        } catch (Exception e) {
            return BrouterResult.failure("Routing threw: " + e.getMessage());
        }

        String error = engine.getErrorMessage();
        if (error != null) return BrouterResult.failure(error);

        OsmTrack track = engine.getFoundTrack();
        if (track == null || track.nodes.isEmpty()) {
            String msg = engine.getFoundInfo();
            return BrouterResult.failure("No route: " + (msg != null ? msg : "no output"));
        }

        List<OsmPathElement> nodes = track.nodes;
        ArrayList<double[]> result = new ArrayList<>(nodes.size());
        for (OsmPathElement node : nodes) {
            double lon = node.getILon() / 1_000_000.0 - 180.0;
            double lat = node.getILat() / 1_000_000.0 - 90.0;
            result.add(new double[]{lat, lon});
        }
        return BrouterResult.success(result);
    }

    private static OsmNodeNamed createWaypoint(double lat, double lon, String name) {
        OsmNodeNamed wp = new OsmNodeNamed();
        wp.name = name;
        wp.ilon = (int) ((lon + 180.0) * 1_000_000 + 0.5);
        wp.ilat = (int) ((lat + 90.0) * 1_000_000 + 0.5);
        return wp;
    }
}
