package com.echidna.studio.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;

import org.junit.Test;

import java.util.Random;

/**
 * The most important test of the project: the user blinks, the model blinks; the user turns the
 * head, the model turns the head. Everything here is driven by synthetic signals, exactly like the
 * ones the camera produces.
 */
public class TrackingMapperTest {

    private static final float STEP = 1.0f / 60.0f;

    private static FaceSignals signals() {
        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        signals.eyeLeft = 1.0f;
        signals.eyeRight = 1.0f;
        signals.blendshapes = true;
        return signals;
    }

    /** Runs the mapper for {@code seconds} with a constant signal and returns the final pose. */
    private static Pose settle(TrackingMapper mapper, FaceSignals signals, float seconds) {
        final Pose pose = new Pose();
        final int frames = Math.round(seconds / STEP);
        for (int i = 0; i < frames; i++) {
            mapper.onSignals(signals, STEP);
            mapper.pose(STEP, pose);
        }
        return pose;
    }

    @Test
    public void turningTheHeadTurnsTheModel() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals signals = signals();
        signals.yaw = 25.0f;
        final Pose pose = settle(mapper, signals, 1.5f);
        assertTrue("модель не повернула голову: " + pose.angleY, Math.abs(pose.angleY) > 12.0f);
        assertTrue("корпус не подключился: " + pose.bodyX, Math.abs(pose.bodyX) > 2.0f);
        assertTrue("зрачки не поехали: " + pose.eyeBallX, Math.abs(pose.eyeBallX) > 0.2f);
    }

    @Test
    public void mirroringInvertsTheTurn() {
        final FaceSignals signals = signals();
        signals.yaw = 25.0f;

        final TrackingMapper mirrored = new TrackingMapper(true);
        final Pose mirroredPose = settle(mirrored, signals, 1.0f);

        final TrackingMapper plain = new TrackingMapper(false);
        final Pose plainPose = settle(plain, signals, 1.0f);

        assertEquals("зеркальный режим должен менять знак поворота",
                -mirroredPose.angleY, plainPose.angleY, 0.5f);
    }

    @Test
    public void noddingMovesTheHeadTheSameWay() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals signals = signals();
        signals.pitch = 20.0f;
        final Pose pose = settle(mapper, signals, 1.5f);
        // The model's X axis points down, so looking up has to move the head up.
        assertTrue("модель не наклонила голову: " + pose.angleX, pose.angleX < -8.0f);
    }

    @Test
    public void closingTheEyesClosesTheModelEyes() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals signals = signals();

        // Eyes open.
        Pose pose = settle(mapper, signals, 1.0f);
        assertTrue("глаза должны быть открыты: " + pose.eyeLOpen, pose.eyeLOpen > 0.9f);
        assertTrue("вес глаз должен быть включён", pose.eyeWeight > 0.9f);

        // Blink: the eyes must close quickly, within a few frames.
        signals.eyeLeft = 0.02f;
        signals.eyeRight = 0.02f;
        for (int i = 0; i < 6; i++) {
            mapper.onSignals(signals, STEP);
            mapper.pose(STEP, pose);
        }
        assertTrue("модель не моргнула: " + pose.eyeLOpen, pose.eyeLOpen < 0.35f);
        assertTrue("правый глаз не моргнул: " + pose.eyeROpen, pose.eyeROpen < 0.35f);

        // And open again.
        signals.eyeLeft = 1.0f;
        signals.eyeRight = 1.0f;
        pose = settle(mapper, signals, 0.6f);
        assertTrue("глаза не открылись обратно: " + pose.eyeLOpen, pose.eyeLOpen > 0.85f);
    }

    @Test
    public void oneEyeWinkStaysOneEyed() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals signals = signals();
        for (int i = 0; i < 30; i++) {
            mapper.onSignals(signals, STEP);
            mapper.pose(STEP, new Pose());
        }
        // The user closes only their right eye.
        signals.eyeRight = 0.0f;
        final Pose pose = settle(mapper, signals, 0.35f);
        assertTrue("подмигивание не дошло", pose.eyeLOpen > 0.75f || pose.eyeROpen > 0.75f);
        assertTrue("оба глаза закрылись как при моргании",
                Math.abs(pose.eyeLOpen - pose.eyeROpen) > 0.25f);
    }

    @Test
    public void smileAndOpenMouthReachTheModel() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals signals = signals();
        signals.smile = 0.9f;
        signals.mouthOpen = 0.8f;
        final Pose pose = settle(mapper, signals, 1.5f);
        assertTrue("улыбка не дошла: " + pose.mouthForm, pose.mouthForm > 0.35f);
        assertTrue("открытый рот не дошёл: " + pose.mouthOpenY, pose.mouthOpenY > 0.4f);
        assertTrue("щёки не подключились: " + pose.cheek, pose.cheek > 0.1f);
    }

    @Test
    public void valuesNeverLeaveTheModelRange() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals signals = signals();
        final Pose pose = new Pose();
        final Random random = new Random(42);
        for (int frame = 0; frame < 4000; frame++) {
            signals.yaw = (random.nextFloat() * 2 - 1) * 90.0f;
            signals.pitch = (random.nextFloat() * 2 - 1) * 90.0f;
            signals.roll = (random.nextFloat() * 2 - 1) * 90.0f;
            signals.eyeLeft = random.nextFloat();
            signals.eyeRight = random.nextFloat();
            signals.smile = random.nextFloat();
            signals.mouthOpen = random.nextFloat();
            signals.centerX = random.nextFloat() * 2 - 1;
            signals.centerY = random.nextFloat() * 2 - 1;
            mapper.onSignals(signals, STEP);
            mapper.pose(STEP, pose);

            assertTrue("угол Y вышел за диапазон модели", pose.angleY >= ParamLimits.ANGLE_Y_MIN - 0.01f
                    && pose.angleY <= ParamLimits.ANGLE_Y_MAX + 0.01f);
            assertTrue("угол X вышел за диапазон модели", pose.angleX >= ParamLimits.ANGLE_X_MIN - 0.01f
                    && pose.angleX <= ParamLimits.ANGLE_X_MAX + 0.01f);
            assertTrue("угол Z вышел за диапазон модели", pose.angleZ >= ParamLimits.ANGLE_Z_MIN - 0.01f
                    && pose.angleZ <= ParamLimits.ANGLE_Z_MAX + 0.01f);
            assertTrue("глаза вне диапазона", pose.eyeLOpen >= 0.0f && pose.eyeLOpen <= 1.0f);
            assertTrue("рот вне диапазона", pose.mouthOpenY >= 0.0f && pose.mouthOpenY <= 1.0f);
            assertFalse("NaN в позе", Float.isNaN(pose.angleX + pose.angleY + pose.angleZ
                    + pose.mouthOpenY + pose.eyeLOpen + pose.eyeBallX));
        }
    }

    @Test
    public void losingTheFaceSwitchesToTheDemoPose() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals signals = signals();
        signals.yaw = 25.0f;
        Pose pose = settle(mapper, signals, 1.5f);
        assertTrue("вес демо должен быть нулевым при живом лице: " + mapper.demoBlend(),
                mapper.demoBlend() < 0.05f);
        assertTrue(mapper.faceLive());
        final float trackedYaw = pose.angleY;

        // The face disappears.
        final FaceSignals lost = new FaceSignals();
        lost.found = false;
        pose = settle(mapper, lost, 3.0f);
        assertFalse("трекер должен понимать, что лицо потеряно", mapper.faceLive());
        assertTrue("демо-режим не включился: " + mapper.demoBlend(), mapper.demoBlend() > 0.9f);
        assertTrue("модель застыла в последней позе",
                Math.abs(pose.angleY - trackedYaw) > 0.5f
                        || Math.abs(pose.angleX) > 0.2f);
    }

    @Test
    public void holdingStillKeepsTheModelStill() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals signals = signals();
        final Pose first = settle(mapper, signals, 2.0f);
        final float yaw = first.angleY;
        final Pose later = settle(mapper, signals, 1.0f);
        assertEquals("модель должна стоять спокойно", yaw, later.angleY, 0.2f);
    }
}
