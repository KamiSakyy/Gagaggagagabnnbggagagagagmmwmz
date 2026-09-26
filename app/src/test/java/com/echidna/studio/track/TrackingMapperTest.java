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

    /**
     * The mapper the tests use: automatic calibration is switched off, because these tests check how
     * an absolute angle of the head reaches the model. The calibration itself has its own test.
     */
    private static TrackingMapper plain(TrackingMapper mapper) {
        mapper.setAutoCalibration(false);
        return mapper;
    }

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
        final TrackingMapper mapper = plain(new TrackingMapper(true));
        final FaceSignals signals = signals();
        signals.yaw = 25.0f;
        final Pose pose = settle(mapper, signals, 1.5f);
        assertTrue("модель не повернула голову в сторону: " + pose.angleX,
                Math.abs(pose.angleX) > 12.0f);
        assertTrue("поворот ушёл в вертикальную ось: " + pose.angleY,
                Math.abs(pose.angleY) < Math.abs(pose.angleX));
        assertTrue("корпус не подключился: " + pose.bodyX, Math.abs(pose.bodyX) > 2.0f);
        assertTrue("зрачки не поехали: " + pose.eyeBallX, Math.abs(pose.eyeBallX) > 0.2f);
    }

    @Test
    public void mirroringInvertsTheTurn() {
        final FaceSignals signals = signals();
        signals.yaw = 25.0f;

        final TrackingMapper mirrored = plain(new TrackingMapper(true));
        final Pose mirroredPose = settle(mirrored, signals, 1.0f);

        final TrackingMapper plain = plain(new TrackingMapper(false));
        final Pose plainPose = settle(plain, signals, 1.0f);

        assertEquals("зеркальный режим должен менять знак поворота",
                -mirroredPose.angleX, plainPose.angleX, 0.5f);
    }

    @Test
    public void noddingMovesTheHeadTheSameWay() {
        final TrackingMapper mapper = plain(new TrackingMapper(true));
        final FaceSignals signals = signals();
        signals.pitch = 20.0f;
        final Pose pose = settle(mapper, signals, 1.5f);
        // The model's X axis points down, so looking up has to move the head up.
        assertTrue("модель не кивнула: " + pose.angleY, pose.angleY < -8.0f);
        assertTrue("кивок уехал в горизонтальную ось: " + pose.angleX,
                Math.abs(pose.angleX) < Math.abs(pose.angleY));
    }

    @Test
    public void closingTheEyesClosesTheModelEyes() {
        final TrackingMapper mapper = plain(new TrackingMapper(true));
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
        final TrackingMapper mapper = plain(new TrackingMapper(true));
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
        final TrackingMapper mapper = plain(new TrackingMapper(true));
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
        final TrackingMapper mapper = plain(new TrackingMapper(true));
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
        final TrackingMapper mapper = plain(new TrackingMapper(true));
        final FaceSignals signals = signals();
        signals.yaw = 25.0f;
        Pose pose = settle(mapper, signals, 1.5f);
        assertTrue("вес демо должен быть нулевым при живом лице: " + mapper.demoBlend(),
                mapper.demoBlend() < 0.05f);
        assertTrue(mapper.faceLive());
        final float trackedYaw = pose.angleX;

        // The face disappears.
        final FaceSignals lost = new FaceSignals();
        lost.found = false;
        pose = settle(mapper, lost, 3.0f);
        assertFalse("трекер должен понимать, что лицо потеряно", mapper.faceLive());
        assertTrue("демо-режим не включился: " + mapper.demoBlend(), mapper.demoBlend() > 0.9f);
        assertTrue("модель застыла в последней позе",
                Math.abs(pose.angleX - trackedYaw) > 0.5f
                        || Math.abs(pose.angleY) > 0.2f);
    }

    @Test
    public void holdingStillKeepsTheModelStill() {
        final TrackingMapper mapper = plain(new TrackingMapper(true));
        final FaceSignals signals = signals();
        final Pose first = settle(mapper, signals, 2.0f);
        final float yaw = first.angleX;
        final Pose later = settle(mapper, signals, 1.0f);
        assertEquals("модель должна стоять спокойно", yaw, later.angleX, 0.2f);
    }

    @Test
    public void theModelFollowsTheUserSideways() {
        final TrackingMapper mapper = plain(new TrackingMapper(true));

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
        final TrackingMapper mapper = plain(new TrackingMapper(true));

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
        final TrackingMapper mapper = plain(new TrackingMapper(true));
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

    // ------------------------------------------------------------- тело, руки, калибровка

    /**
     * Поворот корпуса человека обязан повернуть всю модель, а не только шею: это то самое
     * «я поворачиваюсь, и моделька тоже».
     */
    @Test
    public void turningTheShouldersTurnsTheWholeCharacter() {
        final TrackingMapper mapper = plain(new TrackingMapper(true));
        final FaceSignals signals = signals();
        signals.scale = 0.34f;
        signals.body = true;
        signals.bodyYaw = 30.0f;
        signals.bodyRoll = 8.0f;
        signals.bodyShift = 0.3f;
        final Pose pose = settle(mapper, signals, 1.5f);

        assertTrue("корпус не поехал за плечами: " + pose.bodyX, Math.abs(pose.bodyX) > 6.0f);
        assertTrue("наклон тела не передался: " + pose.bodyZ, Math.abs(pose.bodyZ) > 2.0f);
        assertTrue("смещение тела потерялось", Math.abs(pose.bodyX) > Math.abs(pose.angleX));
    }

    /** Поднятая рука не остаётся незамеченной: у моделей нет рук, поэтому отвечает лицо. */
    @Test
    public void aRaisedHandMakesTheFaceHappy() {
        final TrackingMapper mapper = plain(new TrackingMapper(true));
        final FaceSignals calm = signals();
        calm.scale = 0.34f;
        calm.body = true;
        final Pose calmPose = settle(mapper, calm, 1.0f);

        final TrackingMapper happy = plain(new TrackingMapper(true));
        final FaceSignals raised = signals();
        raised.scale = 0.34f;
        raised.body = true;
        raised.handUp = 1.0f;
        final Pose happyPose = settle(happy, raised, 1.0f);

        assertTrue("поднятая рука не подняла настроение: "
                        + calmPose.mouthForm + " -> " + happyPose.mouthForm,
                happyPose.mouthForm > calmPose.mouthForm + 0.2f);
        assertTrue("улыбка не усилилась", happyPose.cheek > calmPose.cheek);
    }

    /**
     * Руки человека доходят до модели отдельным каналом: объёмный персонаж поднимает руки вместе с
     * пользователем, и это единственный канал, который для этого используется.
     */
    @Test
    public void aRaisedHandRaisesTheArms() {
        final TrackingMapper mapper = plain(new TrackingMapper(true));
        final FaceSignals calm = signals();
        calm.scale = 0.34f;
        calm.body = true;
        final Pose calmPose = settle(mapper, calm, 1.0f);
        assertEquals("без поднятых рук канал рук должен стоять на нуле",
                0f, calmPose.armY, 0.02f);

        final TrackingMapper lifted = plain(new TrackingMapper(true));
        final FaceSignals raised = signals();
        raised.scale = 0.34f;
        raised.body = true;
        raised.handUp = 1.0f;
        final Pose raisedPose = settle(lifted, raised, 1.0f);

        assertTrue("поднятая рука не подняла руки модели: " + raisedPose.armY,
                raisedPose.armY > 0.7f);
        assertTrue("канал рук вышел за диапазон 0..1: " + raisedPose.armY,
                raisedPose.armY <= 1.0f);

        // Опущенная рука возвращает руки на место, а не оставляет модель с поднятыми.
        final Pose back = settle(lifted, calm, 3.0f);
        assertTrue("руки не опустились обратно: " + back.armY, back.armY < 0.1f);
    }

    /**
     * Калибровка: человек сидит, повернув голову на десять градусов вбок. После съёма нейтрали
     * модель смотрит прямо, а не повторяет эту позу постоянно.
     */
    @Test
    public void calibrationRemovesTheBiasOfTheUser() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals bias = signals();
        bias.scale = 0.34f;
        bias.yaw = 12.0f;
        bias.roll = -4.0f;
        settle(mapper, bias, 1.0f);
        assertTrue("нейтраль должна сняться за секунду", mapper.isCalibrated());

        final Pose pose = settle(mapper, bias, 1.0f);
        assertTrue("модель осталась повёрнутой из-за позы человека: " + pose.angleX,
                Math.abs(pose.angleX) < 2.0f);

        // А теперь человек реально поворачивает голову: уже относительно своей нейтрали.
        final FaceSignals turned = signals();
        turned.scale = 0.34f;
        turned.yaw = 37.0f;
        final Pose turnedPose = settle(mapper, turned, 1.5f);
        assertTrue("поворот относительно нейтрали не сработал: " + turnedPose.angleX,
                Math.abs(turnedPose.angleX) > 12.0f);
    }

    /** Пока лицо не найдено, нейтраль не снимается: снимать её не с чего. */
    @Test
    public void calibrationWaitsForARealFace() {
        final TrackingMapper mapper = new TrackingMapper(true);
        final FaceSignals lost = new FaceSignals();
        lost.found = false;
        settle(mapper, lost, 2.0f);
        assertFalse("нейтраль снялась без лица", mapper.isCalibrated());

        final FaceSignals face = signals();
        face.scale = 0.34f;
        settle(mapper, face, 1.2f);
        assertTrue("нейтраль не снялась с живым лицом", mapper.isCalibrated());
    }

    /** Мимика из blendshape: поджатые губы и прищур попадают в позу модели. */
    @Test
    public void blendshapesReachTheMouthAndTheEyes() {
        final TrackingMapper plainFace = plain(new TrackingMapper(true));
        final FaceSignals neutral = signals();
        neutral.scale = 0.34f;
        final Pose neutralPose = settle(plainFace, neutral, 1.0f);

        final TrackingMapper expressive = plain(new TrackingMapper(true));
        final FaceSignals pucker = signals();
        pucker.scale = 0.34f;
        pucker.blendMouthPucker = 0.9f;
        pucker.blendEyeSquintLeft = 0.8f;
        pucker.blendEyeSquintRight = 0.8f;
        expressive.onBlendshapes(pucker);
        final Pose puckerPose = settle(expressive, pucker, 1.0f);

        assertTrue("поджатые губы не изменили форму рта",
                Math.abs(puckerPose.mouthForm - neutralPose.mouthForm) > 0.1f);
        assertTrue("прищур не дошёл до глаз", puckerPose.eyeLSmile > neutralPose.eyeLSmile + 0.1f);
    }

    /**
     * Тело вместо лица: когда датчик лица потерял пользователя, но поза видна, персонаж продолжает
     * жить и поворачиваться, а не уходит в демонстрационное покачивание. Именно такую рамку собирает
     * TrackingHub: лицо потеряно, тело найдено, поэтому в ней found уже true, а poseOnly - метка.
     */
    @Test
    public void theBodyKeepsTheCharacterAliveWhenTheFaceIsLost() {
        final TrackingMapper mapper = plain(new TrackingMapper(true));
        final FaceSignals bodyOnly = new FaceSignals();
        bodyOnly.found = true;
        bodyOnly.poseOnly = true;
        bodyOnly.body = true;
        bodyOnly.bodyYaw = 35.0f;
        bodyOnly.yaw = 20.0f;
        final Pose pose = settle(mapper, bodyOnly, 1.5f);

        assertTrue("без лица корпус не двигает модель: " + pose.bodyX, Math.abs(pose.bodyX) > 4.0f);
        assertTrue("голова не пошла за телом: " + pose.angleX, Math.abs(pose.angleX) > 8.0f);
        assertTrue("демо-покачивание перебило живого человека: " + mapper.demoBlend(),
                mapper.demoBlend() < 0.2f);
    }
}
