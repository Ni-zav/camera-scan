package dev.nizav.documentscanner.camera;

public final class AutoCaptureGate {
    private static final int REQUIRED_GOOD_FRAMES = 7;
    private static final long COOLDOWN_MS = 1800L;
    private static final float MIN_DETECTION_SCORE = 0.42f;

    private int goodFrames;
    private long lastTriggerMs;

    public boolean update(
            boolean detectedThisFrame,
            boolean geometricallyStable,
            float detectionScore,
            FrameQuality quality,
            long nowMs
    ) {
        boolean ready = detectedThisFrame
                && geometricallyStable
                && detectionScore >= MIN_DETECTION_SCORE
                && quality != null
                && quality.acceptable();

        if (!ready) {
            goodFrames = 0;
            return false;
        }

        goodFrames++;
        if (goodFrames < REQUIRED_GOOD_FRAMES) {
            return false;
        }

        if (nowMs - lastTriggerMs < COOLDOWN_MS) {
            return false;
        }

        goodFrames = 0;
        lastTriggerMs = nowMs;
        return true;
    }

    public void reset() {
        goodFrames = 0;
    }
}
