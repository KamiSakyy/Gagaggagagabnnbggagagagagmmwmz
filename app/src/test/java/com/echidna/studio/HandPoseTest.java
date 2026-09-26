package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.echidna.studio.track.HandPose;

import org.junit.Test;

/**
 * The hand math is what decides whether the avatar shows four fingers or a fist, so every gesture the
 * user asked for is spelled out here as 21 points, exactly like the hand model reports them.
 *
 * <p>The hands below are drawn in image coordinates: X to the right, Y downwards. A hand held
 * sideways and a hand held upside down are included on purpose - on a phone the user's hand is
 * rarely upright.</p>
 */
public class HandPoseTest {

    /** Строит кисть по «скелету»: точка запястья, направления пальцев и их длины. */
    private static float[][] hand(float wristX, float wristY,
                                  float[] fingerDirsX, float[] fingerDirsY,
                                  float fingerLength, boolean thumbOut) {
        final float[] xs = new float[HandPose.POINTS];
        final float[] ys = new float[HandPose.POINTS];
        xs[HandPose.WRIST] = wristX;
        ys[HandPose.WRIST] = wristY;
        // Основания пальцев лежат веером вокруг середины ладони.
        final int[] mcp = {HandPose.INDEX_MCP, HandPose.MIDDLE_MCP, HandPose.RING_MCP, HandPose.PINKY_MCP};
        final int[] pip = {HandPose.INDEX_PIP, HandPose.MIDDLE_PIP, HandPose.RING_PIP, HandPose.PINKY_PIP};
        final int[] tip = {HandPose.INDEX_TIP, HandPose.MIDDLE_TIP, HandPose.RING_TIP, HandPose.PINKY_TIP};
        for (int i = 0; i < 4; i++) {
            xs[mcp[i]] = wristX + fingerDirsX[i] * 0.3f;
            ys[mcp[i]] = wristY + fingerDirsY[i] * 0.3f;
            // Выпрямленный палец: средний сустав на середине, кончик на длине пальца.
            xs[pip[i]] = xs[mcp[i]] + fingerDirsX[i] * fingerLength * 0.5f;
            ys[pip[i]] = ys[mcp[i]] + fingerDirsY[i] * fingerLength * 0.5f;
            xs[tip[i]] = xs[mcp[i]] + fingerDirsX[i] * fingerLength;
            ys[tip[i]] = ys[mcp[i]] + fingerDirsY[i] * fingerLength;
        }
        xs[HandPose.THUMB_CMC] = wristX + 0.1f;
        ys[HandPose.THUMB_CMC] = wristY;
        xs[HandPose.THUMB_MCP] = wristX + (thumbOut ? 0.22f : 0.12f);
        ys[HandPose.THUMB_MCP] = wristY + 0.1f;
        xs[HandPose.THUMB_IP] = wristX + (thumbOut ? 0.3f : 0.14f);
        ys[HandPose.THUMB_IP] = wristY + 0.18f;
        xs[HandPose.THUMB_TIP] = wristX + (thumbOut ? 0.36f : 0.15f);
        ys[HandPose.THUMB_TIP] = wristY + 0.26f;
        return new float[][]{xs, ys};
    }

    private static final float[] UP_X = {0.0f, 0.0f, 0.0f, 0.0f};
    private static final float[] UP_Y = {-1.0f, -1.0f, -1.0f, -1.0f};

    @Test
    public void openPalmShowsFive() {
        final float[][] h = hand(0.5f, 0.8f, UP_X, UP_Y, 0.3f, true);
        assertEquals(5, HandPose.fingerCount(h[0], h[1]));
    }

    @Test
    public void fistShowsZero() {
        final float[] xs = new float[HandPose.POINTS];
        final float[] ys = new float[HandPose.POINTS];
        xs[HandPose.WRIST] = 0.5f;
        ys[HandPose.WRIST] = 0.8f;
        // Все четыре пальца загнуты к ладони: кончик ближе к запястью, чем средний сустав.
        final int[] mcp = {HandPose.INDEX_MCP, HandPose.MIDDLE_MCP, HandPose.RING_MCP, HandPose.PINKY_MCP};
        final int[] pip = {HandPose.INDEX_PIP, HandPose.MIDDLE_PIP, HandPose.RING_PIP, HandPose.PINKY_PIP};
        final int[] tip = {HandPose.INDEX_TIP, HandPose.MIDDLE_TIP, HandPose.RING_TIP, HandPose.PINKY_TIP};
        for (int i = 0; i < 4; i++) {
            xs[mcp[i]] = 0.5f + i * 0.01f;
            ys[mcp[i]] = 0.72f;
            xs[pip[i]] = 0.5f + i * 0.01f;
            ys[pip[i]] = 0.68f;
            xs[tip[i]] = 0.5f + i * 0.01f;
            ys[tip[i]] = 0.71f;
        }
        xs[HandPose.THUMB_TIP] = 0.52f;
        ys[HandPose.THUMB_TIP] = 0.74f;
        xs[HandPose.THUMB_IP] = 0.53f;
        ys[HandPose.THUMB_IP] = 0.76f;
        xs[HandPose.PINKY_MCP] = 0.53f;
        ys[HandPose.PINKY_MCP] = 0.72f;
        assertEquals(0, HandPose.fingerCount(xs, ys));
    }

    @Test
    public void fourFingersReadAsFour() {
        final float[][] h = hand(0.5f, 0.8f, UP_X, UP_Y, 0.3f, false);
        // Большой палец прижат к ладони: его кончик не уходит от основания мизинца.
        h[0][HandPose.THUMB_TIP] = 0.5f;
        h[1][HandPose.THUMB_TIP] = 0.79f;
        h[0][HandPose.THUMB_IP] = 0.51f;
        h[1][HandPose.THUMB_IP] = 0.795f;
        assertEquals(4, HandPose.fingerCount(h[0], h[1]));
    }

    @Test
    public void victoryReadsAsTwo() {
        final float[][] h = hand(0.5f, 0.8f, UP_X, UP_Y, 0.3f, false);
        // Загибаем безымянный и мизинец.
        for (int i : new int[]{2, 3}) {
            final int tip = i == 2 ? HandPose.RING_TIP : HandPose.PINKY_TIP;
            final int mcp = i == 2 ? HandPose.RING_MCP : HandPose.PINKY_MCP;
            h[0][tip] = h[0][mcp];
            h[1][tip] = h[1][mcp] + 0.01f;
        }
        h[0][HandPose.THUMB_TIP] = 0.5f;
        h[1][HandPose.THUMB_TIP] = 0.79f;
        h[0][HandPose.THUMB_IP] = 0.51f;
        h[1][HandPose.THUMB_IP] = 0.795f;
        assertEquals(2, HandPose.fingerCount(h[0], h[1]));
    }

    @Test
    public void sidewaysHandStillCounts() {
        // Рука повёрнута на 90 градусов: указательный палец смотрит вправо.
        final float[] dx = {1.0f, 1.0f, 1.0f, 1.0f};
        final float[] dy = {0.0f, 0.0f, 0.0f, 0.0f};
        final float[][] h = hand(0.2f, 0.5f, dx, dy, 0.3f, true);
        assertEquals(5, HandPose.fingerCount(h[0], h[1]));
    }

    @Test
    public void upsideDownHandStillCounts() {
        final float[] dx = {0.0f, 0.0f, 0.0f, 0.0f};
        final float[] dy = {1.0f, 1.0f, 1.0f, 1.0f};
        final float[][] h = hand(0.5f, 0.2f, dx, dy, 0.3f, true);
        assertEquals(5, HandPose.fingerCount(h[0], h[1]));
    }

    @Test
    public void brokenDataReadsAsZero() {
        assertEquals(0, HandPose.fingerCount(null, null));
        assertEquals(0, HandPose.fingerCount(new float[3], new float[3]));
    }

    @Test
    public void chinTouchIsRecognised() {
        final float[][] h = hand(0.5f, 0.8f, UP_X, UP_Y, 0.3f, true);
        // Подбородок в точке (0.5, 0.5), высота лица 0.3: сначала рука далеко внизу, потом у лица.
        assertFalse(HandPose.touches(h[0], h[1], 0.5f, -0.3f, 0.3f));
        // Рука поднята к подбородку: кончик указательного попадает в точку.
        h[0][HandPose.INDEX_TIP] = 0.5f;
        h[1][HandPose.INDEX_TIP] = 0.51f;
        assertTrue(HandPose.touches(h[0], h[1], 0.5f, 0.5f, 0.3f));
        assertEquals(1.0f, HandPose.reach(h[0], h[1], 0.5f, 0.5f, 0.3f), 0.001f);
    }

    @Test
    public void reachFadesWithDistance() {
        final float[][] h = hand(0.5f, 0.8f, UP_X, UP_Y, 0.3f, true);
        final float near = HandPose.reach(h[0], h[1], 0.5f, 0.52f, 0.3f);
        final float far = HandPose.reach(h[0], h[1], 0.5f, -0.15f, 0.3f);
        assertTrue("близко должно быть больше, чем далеко", near > far);
        assertEquals(1.0f, near, 0.001f);
        assertEquals(0.0f, far, 0.001f);
        assertEquals(0.0f, HandPose.reach(h[0], h[1], 0.5f, 0.5f, 0.0f), 0.001f);
    }

    @Test
    public void pinchIsSmallWhenFingersMeet() {
        final float[][] h = hand(0.5f, 0.8f, UP_X, UP_Y, 0.3f, true);
        assertTrue(HandPose.pinchDistance(h[0], h[1]) > 0.1f);
        h[0][HandPose.THUMB_TIP] = h[0][HandPose.INDEX_TIP];
        h[1][HandPose.THUMB_TIP] = h[1][HandPose.INDEX_TIP];
        assertEquals(0.0f, HandPose.pinchDistance(h[0], h[1]), 0.0001f);
    }

    @Test
    public void palmSitsBetweenWristAndKnuckles() {
        final float[][] h = hand(0.5f, 0.8f, UP_X, UP_Y, 0.3f, true);
        assertTrue(HandPose.palmX(h[0], h[1]) > 0.0f);
        assertTrue(HandPose.palmY(h[1]) < 0.8f);
        assertTrue(HandPose.span(h[0], h[1]) > 0.0f);
    }
}
