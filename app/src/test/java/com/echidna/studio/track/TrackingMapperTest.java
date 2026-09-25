package com.echidna.studio.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void theModelFollowsTheUserSideways() {
        final TrackingMapper mapper = new TrackingMapper(true);

        // The user's head is at their right, which is the left half of the raw camera frame.
        final FaceSignals right = signals();
        right.centerX = -0.6f;
        final Pose shifted = settle(mapper, right, 1.5f);
        assertTrue("модель должна поехать вправо вместе с человеком: " + shifted.offsetX,
                shifted.offsetX > 0.01f);

        // And to the other side.
        final FaceSignals left = signals();
        left.centerX = 0.6f;
        final Pose other = settle(mapper, left, 2.0f);
        assertTrue("модель должна поехать влево: " + other.offsetX, other.offsetX < -0.01f);
        assertTrue("сдвиг должен оставаться в разумных границах",
                Math.abs(other.offsetX) <= ParamLimits.OFFSET_MAX + 0.0001f);
    }

    @Test
    public void leaningInMakesTheModelBigger() {
        final TrackingMapper mapper = new TrackingMapper(true);

        final FaceSignals normal = signals();
        normal.scale = 0.34f;
        final Pose neutral = settle(mapper, normal, 2.0f);
        assertEquals("на обычном расстоянии размер не меняется", 1.0f, neutral.zoom, 0.02f);

        final FaceSignals close = signals();
        close.scale = 0.55f;
        final Pose zoomed = settle(mapper, close, 2.0f);
        assertTrue("приближение должно увеличивать модель: " + zoomed.zoom, zoomed.zoom > 1.02f);
        assertTrue("увеличение должно быть ограничено", zoomed.zoom <= ParamLimits.ZOOM_MAX + 0.0001f);

        final FaceSignals far = signals();
        far.scale = 0.18f;
        final Pose small = settle(mapper, far, 2.0f);
        assertTrue("отъезд должен уменьшать модель: " + small.zoom, small.zoom < 0.99f);
        assertTrue("уменьшение должно быть ограничено", small.zoom >= ParamLimits.ZOOM_MIN - 0.0001f);
    }

    @Test
    public void posesWithoutTrackingKeepTheModelCentred() {
        // Shows and the idle director build poses from scratch: they must not move the model, or
        // the authored framing would change whenever the camera mode was used before.
        final Pose authored = new Pose();
        assertFalse("авторская поза не должна двигать модель", authored.shiftsModel());
        assertEquals(1.0f, authored.zoom, 0.0001f);

        final Pose copy = new Pose();
        final Pose tracked = new Pose();
        tracked.offsetX = 0.08f;
        tracked.zoom = 1.1f;
        Pose.lerp(authored, tracked, 0.5f, copy);
        assertEquals("половинный переход даёт половинный сдвиг", 0.04f, copy.offsetX, 0.0001f);
        assertEquals(1.05f, copy.zoom, 0.0001f);

        copy.reset();
        assertFalse("сброс возвращает модель в центр", copy.shiftsModel());
    }

    @Test
    public void aTrackerWithoutBlinksHandsTheEyelidsToTheFramework() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals alwaysOpen = signals();
        alwaysOpen.eyeLeft = 1.0f;
        alwaysOpen.eyeRight = 1.0f;
        final Pose pose = settle(mapper, alwaysOpen, 8.0f);

        assertTrue("трекер без морганий должен быть распознан", mapper.blinkStarved());
        assertEquals("веки должны перейти движку", 0.0f, pose.eyeWeight, 0.001f);

        // As soon as a real blink arrives, control goes back to the tracker.
        final FaceSignals blink = signals();
        blink.eyeLeft = 0.1f;
        blink.eyeRight = 0.1f;
        mapper.onSignals(blink, STEP);
        mapper.pose(STEP, pose);
        assertFalse("после моргания управление возвращается трекеру", mapper.blinkStarved());
        assertEquals(1.0f, pose.eyeWeight, 0.001f);
        assertTrue("глаза должны закрыться по трекеру", pose.eyeLOpen < 0.6f);
    }
}
