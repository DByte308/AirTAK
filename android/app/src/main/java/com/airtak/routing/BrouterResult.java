package com.airtak.routing;

import java.util.List;

public final class BrouterResult {
    public final List<double[]> points;
    public final String error;

    private BrouterResult(List<double[]> points, String error) {
        this.points = points;
        this.error = error;
    }

    public static BrouterResult success(List<double[]> points) {
        return new BrouterResult(points, null);
    }

    public static BrouterResult failure(String error) {
        return new BrouterResult(null, error);
    }

    public boolean isSuccess() { return error == null; }
}
