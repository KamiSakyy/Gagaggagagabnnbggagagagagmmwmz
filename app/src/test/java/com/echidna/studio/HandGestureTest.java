package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.echidna.studio.anim.Pose;
import com.echidna.studio.track.FaceSignals;
import com.echidna.studio.track.TrackingMapper;

import org.junit.Test;

/**
 * Руки насквозь: то, что видит трекер кисти, обязано доходить до позы модели.
 *
 * <p>Проверяются три обещания: поднятая рука поднимает руку модели (канал armY), касание
 * подбородка видно в pose.chinTouch, а показанные пальцы превращаются в кивки головой. Всё это
 * чистая математика, поэтому проверяется без камеры и без модели.</p>
 */
public class HandGestureTest {
    private static final float STEP = 1.0f / 30.0f;

    private static FaceSignals frame(int fingers, float chinTouch, float handUp) {
        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        signals.scale = 0.34f;
        signals.eyeLeft = 1.0f;
        signals.eyeRight = 1.0f;
        signals.handsSeen = true;
        signals.hands = 1;
        signals.fingers = fingers;
        signals.fingersLeft = fingers;
        signals.handOpen = fingers / 5.0f;
        signals.handX = 0.3f;
        signals.handY = -0.1f;
        signals.indexX = 0.3f;
        signals.indexY = -0.2f;
        signals.middleX = 0.35f;
        signals.middleY = -0.2f;
        signals.handUp = handUp;
        signals.chinTouch = chinTouch;
        return signals;
    }

    private static Pose run(TrackingMapper mapper, FaceSignals signals, float seconds) {
        final Pose pose = new Pose();
        for (float t = 0; t < seconds; t += STEP) {
            mapper.onSignals(signals, STEP);
            pose.set(mapper.pose(STEP, pose));
        }
        return pose;
    }

    @Test
    public void raisedHandRaisesTheArmOfTheModel() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final Pose pose = run(mapper, frame(5, 0.0f, 0.9f), 1.5f);
        assertTrue("рука модели не поднялась: armY=" + pose.armY, pose.armY > 0.3f);
        assertTrue("модель не знает про руку", pose.handsSeen);
        assertEquals(5, pose.fingers);
    }

    @Test
    public void handOnTheChinReachesThePose() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final Pose pose = run(mapper, frame(2, 1.0f, 0.2f), 1.5f);
        assertTrue("касание подбородка не дошло: " + pose.chinTouch, pose.chinTouch > 0.6f);
    }

    @Test
    public void fourFingersMakeTheHeadNod() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final FaceSignals signals = frame(4, 0.0f, 0.5f);
        // Спокойная голова: без жеста угол наклона стоит на месте.
        final Pose still = run(mapper, signals, 1.5f);
        final float rest = still.angleY;
        float deepest = rest;
        float shallowest = rest;
        for (float t = 0; t < 3.0f; t += STEP) {
            mapper.onSignals(signals, STEP);
            final Pose pose = new Pose();
            pose.set(mapper.pose(STEP, pose));
            deepest = Math.max(deepest, pose.angleY);
            shallowest = Math.min(shallowest, pose.angleY);
        }
        assertTrue("голова не качнулась: " + deepest + ".." + shallowest,
                deepest - shallowest > 2.0f);
    }

    @Test
    public void noHandsMeansNoArms() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final FaceSignals signals = frame(4, 0.0f, 0.0f);
        signals.handsSeen = false;
        signals.fingers = -1;
        final Pose pose = run(mapper, signals, 2.0f);
        assertEquals(0.0f, pose.armY, 0.02f);
        assertEquals(0.0f, pose.chinTouch, 0.02f);
        assertTrue(!pose.handsSeen);
    }

    @Test
    public void armDirectionIsCarriedToThePose() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        mapper.setArmInverted(true);
        final Pose pose = run(mapper, frame(3, 0.0f, 0.7f), 0.5f);
        assertTrue(pose.armInverted);
    }
}
