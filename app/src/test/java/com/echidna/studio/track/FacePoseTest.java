package com.echidna.studio.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Checks the rotation decomposition that turns the head matrix into angles. */
public class FacePoseTest {

    /** Builds a column major rotation matrix for the given angles in degrees. */
    private static float[] rotation(float pitchDeg, float yawDeg, float rollDeg) {
        final double p = Math.toRadians(pitchDeg);
        final double y = Math.toRadians(yawDeg);
        final double r = Math.toRadians(rollDeg);
        final double[][] rx = {
                {1, 0, 0},
                {0, Math.cos(p), -Math.sin(p)},
                {0, Math.sin(p), Math.cos(p)}
        };
        final double[][] ry = {
                {Math.cos(y), 0, Math.sin(y)},
                {0, 1, 0},
                {-Math.sin(y), 0, Math.cos(y)}
        };
        final double[][] rz = {
                {Math.cos(r), -Math.sin(r), 0},
                {Math.sin(r), Math.cos(r), 0},
                {0, 0, 1}
        };
        final double[][] m = multiply(multiply(rz, ry), rx);
        final float[] out = new float[16];
        out[0] = (float) m[0][0];
        out[1] = (float) m[1][0];
        out[2] = (float) m[2][0];
        out[4] = (float) m[0][1];
        out[5] = (float) m[1][1];
        out[6] = (float) m[2][1];
        out[8] = (float) m[0][2];
        out[9] = (float) m[1][2];
        out[10] = (float) m[2][2];
        out[15] = 1.0f;
        return out;
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        final double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += a[i][k] * b[k][j];
                }
                out[i][j] = sum;
            }
        }
        return out;
    }

    @Test
    public void neutralHeadGivesZeroAngles() {
        final float[] angles = FacePose.eulerFromMatrix(rotation(0, 0, 0));
        assertEquals(0.0f, angles[0], 0.01f);
        assertEquals(0.0f, angles[1], 0.01f);
        assertEquals(0.0f, angles[2], 0.01f);
    }

    @Test
    public void yawIsRecovered() {
        final float[] angles = FacePose.eulerFromMatrix(rotation(0, 18, 0));
        assertEquals("yaw не восстановлен", 18.0f, angles[1], 0.5f);
    }

    @Test
    public void rollIsRecovered() {
        final float[] angles = FacePose.eulerFromMatrix(rotation(0, 0, -12));
        assertEquals("roll не восстановлен", -12.0f, angles[2], 0.5f);
    }

    @Test
    public void pitchIsRecovered() {
        final float[] angles = FacePose.eulerFromMatrix(rotation(14, 0, 0));
        assertEquals("pitch не восстановлен", 14.0f, angles[0], 0.5f);
    }

    @Test
    public void brokenMatricesDoNotProduceNaN() {
        assertEquals(0.0f, FacePose.eulerFromMatrix(null)[0], 0.001f);
        assertEquals(0.0f, FacePose.eulerFromMatrix(new float[3])[1], 0.001f);
        final float[] broken = new float[16];
        broken[0] = Float.NaN;
        broken[5] = Float.NaN;
        final float[] angles = FacePose.eulerFromMatrix(broken);
        assertTrue(!Float.isNaN(angles[0]) && !Float.isNaN(angles[1]) && !Float.isNaN(angles[2]));
    }

    @Test
    public void optionalsAndPlainListsAreUnwrapped() {
        assertTrue(FacePose.asList(null).isEmpty());
        final java.util.List<String> plain = new java.util.ArrayList<String>();
        plain.add("a");
        assertEquals(1, FacePose.asList(plain).size());
        assertEquals(1, FacePose.asList(new String[]{"a"}).size());
        assertTrue(FacePose.asList(java.util.Optional.empty()).isEmpty());
        assertEquals(1, FacePose.asList(java.util.Optional.of("x")).size());
        assertNotNull(FacePose.asList(java.util.Optional.of(plain)));
    }

    @Test
    public void matrixContainerIsUnwrapped() {
        final float[] raw = rotation(0, 0, 0);
        assertNotNull(FacePose.matrixOf(raw));
        assertNotNull(FacePose.matrixOf(java.util.Optional.of(raw)));
    }

    // ------------------------------------------------------- сигналы: тело и поза

    /** Копирование сигналов обязано переносить и данные тела: иначе кнопка «калибровка» их теряет. */
    @Test
    public void copyingSignalsKeepsTheBodyData() {
        final FaceSignals source = new FaceSignals();
        source.found = true;
        source.body = true;
        source.bodyYaw = 21.5f;
        source.bodyRoll = -7.0f;
        source.bodyLift = 0.4f;
        source.bodyShift = -0.3f;
        source.handUp = 0.8f;
        source.poseOnly = true;

        final FaceSignals copy = new FaceSignals();
        copy.set(source);

        assertTrue(copy.body);
        assertEquals(21.5f, copy.bodyYaw, 0.001f);
        assertEquals(-7.0f, copy.bodyRoll, 0.001f);
        assertEquals(0.4f, copy.bodyLift, 0.001f);
        assertEquals(-0.3f, copy.bodyShift, 0.001f);
        assertEquals(0.8f, copy.handUp, 0.001f);
        assertTrue("метка «только тело» потерялась", copy.poseOnly);
    }
}
