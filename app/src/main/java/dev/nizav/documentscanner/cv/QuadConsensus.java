package dev.nizav.documentscanner.cv;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class QuadConsensus {
    private static final int WINDOW = 5;
    private static final int MISSES_TO_CLEAR = 2;

    private final Deque<float[]> history = new ArrayDeque<>(WINDOW);
    private int misses;

    public Quad update(Quad quad) {
        if (quad == null) {
            misses++;
            if (misses >= MISSES_TO_CLEAR) {
                history.clear();
            }
            return history.isEmpty() ? null : median();
        }

        misses = 0;
        if (history.size() == WINDOW) {
            history.removeFirst();
        }
        history.addLast(quad.toFloatArray());
        return median();
    }

    public void reset() {
        history.clear();
        misses = 0;
    }

    private Quad median() {
        int size = history.size();
        if (size == 0) {
            return null;
        }

        float[] output = new float[8];
        float[] values = new float[size];

        for (int coordinate = 0; coordinate < 8; coordinate++) {
            int index = 0;
            for (float[] sample : history) {
                values[index++] = sample[coordinate];
            }
            Arrays.sort(values, 0, size);
            int middle = size / 2;
            output[coordinate] = size % 2 == 1
                    ? values[middle]
                    : (values[middle - 1] + values[middle]) * 0.5f;
        }

        return Quad.fromFloatArray(output);
    }
}
