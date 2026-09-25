package dev.nizav.documentscanner.cv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.opencv.core.Point;

public final class CurvedBoundaryCorrectorTest {
    @Test
    public void fullFrameIsValid() {
        assertTrue(
                CurvedBoundaryCorrector.isValid(
                        Boundary8.fullFrame()
                )
        );
    }

    @Test
    public void moderateBowedEdgesRemainValid() {
        Boundary8 boundary = new Boundary8(new Point[]{
                new Point(0.08, 0.08),
                new Point(0.50, 0.03),
                new Point(0.92, 0.08),
                new Point(0.97, 0.50),
                new Point(0.92, 0.92),
                new Point(0.50, 0.97),
                new Point(0.08, 0.92),
                new Point(0.03, 0.50)
        });

        assertTrue(CurvedBoundaryCorrector.isValid(boundary));
    }

    @Test
    public void crossedCornersAreRejected() {
        Boundary8 boundary = new Boundary8(new Point[]{
                new Point(0.08, 0.08),
                new Point(0.50, 0.08),
                new Point(0.92, 0.92),
                new Point(0.92, 0.50),
                new Point(0.92, 0.08),
                new Point(0.50, 0.92),
                new Point(0.08, 0.92),
                new Point(0.08, 0.50)
        });

        assertFalse(CurvedBoundaryCorrector.isValid(boundary));
    }

    @Test
    public void collapsedBoundaryIsRejected() {
        Boundary8 boundary = new Boundary8(new Point[]{
                new Point(0.45, 0.45),
                new Point(0.50, 0.45),
                new Point(0.55, 0.45),
                new Point(0.55, 0.50),
                new Point(0.55, 0.55),
                new Point(0.50, 0.55),
                new Point(0.45, 0.55),
                new Point(0.45, 0.50)
        });

        assertFalse(CurvedBoundaryCorrector.isValid(boundary));
    }

    @Test
    public void extremeMidpointFoldIsRejected() {
        Boundary8 boundary = new Boundary8(new Point[]{
                new Point(0.08, 0.08),
                new Point(0.50, 0.95),
                new Point(0.92, 0.08),
                new Point(0.92, 0.50),
                new Point(0.92, 0.92),
                new Point(0.50, 0.92),
                new Point(0.08, 0.92),
                new Point(0.08, 0.50)
        });

        assertFalse(CurvedBoundaryCorrector.isValid(boundary));
    }
}
