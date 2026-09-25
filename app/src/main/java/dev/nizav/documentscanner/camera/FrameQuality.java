package dev.nizav.documentscanner.camera;

public final class FrameQuality {
    public final double sharpness;
    public final double luminance;
    public final boolean exposureOk;
    public final boolean sharpnessOk;

    public FrameQuality(
            double sharpness,
            double luminance,
            boolean exposureOk,
            boolean sharpnessOk
    ) {
        this.sharpness = sharpness;
        this.luminance = luminance;
        this.exposureOk = exposureOk;
        this.sharpnessOk = sharpnessOk;
    }

    public boolean acceptable() {
        return exposureOk && sharpnessOk;
    }
}
