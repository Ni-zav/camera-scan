package dev.nizav.documentscanner.cv;

public final class QuadStabilizer {
    private static final float ALPHA = 0.35f;
    private static final float MAX_STABLE_POINT_DELTA = 0.012f;
    private static final int STABLE_FRAMES = 5;
    private static final int MISSED_FRAMES_TO_HIDE = 3;

    private final float[] smoothed = new float[8];
    private boolean initialized;
    private int stableCount;
    private int missedFrames;

    public State update(Quad input) {
        if (input == null) {
            stableCount = 0;
            missedFrames++;
            if (!initialized || missedFrames >= MISSED_FRAMES_TO_HIDE) {
                return new State(null, false);
            }
            return new State(Quad.fromFloatArray(smoothed), false);
        }

        missedFrames = 0;
        float[] next = input.toFloatArray();
        if (!initialized) {
            System.arraycopy(next, 0, smoothed, 0, 8);
            initialized = true;
            stableCount = 0;
            return new State(Quad.fromFloatArray(smoothed), false);
        }

        float maxDelta = 0f;
        for (int i = 0; i < 8; i += 2) {
            float dx = next[i] - smoothed[i];
            float dy = next[i + 1] - smoothed[i + 1];
            maxDelta = Math.max(maxDelta, (float) Math.hypot(dx, dy));
        }

        if (maxDelta <= MAX_STABLE_POINT_DELTA) {
            stableCount++;
        } else {
            stableCount = 0;
        }

        for (int i = 0; i < 8; i++) {
            smoothed[i] += ALPHA * (next[i] - smoothed[i]);
        }

        return new State(
                Quad.fromFloatArray(smoothed),
                stableCount >= STABLE_FRAMES
        );
    }

    public void reset() {
        initialized = false;
        stableCount = 0;
        missedFrames = 0;
    }

    public static final class State {
        public final Quad quad;
        public final boolean stable;

        State(Quad quad, boolean stable) {
            this.quad = quad;
            this.stable = stable;
        }
    }
}
