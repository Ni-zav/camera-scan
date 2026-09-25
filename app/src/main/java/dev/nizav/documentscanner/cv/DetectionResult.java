package dev.nizav.documentscanner.cv;

public final class DetectionResult {
    public final Quad quad;
    public final float score;

    public DetectionResult(Quad quad, float score) {
        this.quad = quad;
        this.score = score;
    }
}
