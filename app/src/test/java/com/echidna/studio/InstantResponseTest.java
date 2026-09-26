package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.echidna.studio.anim.Pose;
import com.echidna.studio.track.FaceSignals;
import com.echidna.studio.track.TrackingMapper;

import org.junit.Test;

/**
 * Модель обязана повторять движение сразу, а не «через полсекунды».
 *
 * <p>Здесь закреплены три вещи, из-за которых раньше было вяло: сила отклика (поворот головы
 * человека превращается в заметный поворот головы модели), скорость отклика (десятая доля секунды,
 * а не полсекунды) и раздельность рук (поднятая одна рука не тянет за собой другую).</p>
 */
public class InstantResponseTest {
    private static final float STEP = 1.0f / 60.0f;

    private static FaceSignals head(float yaw, float pitch, float roll) {
        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        signals.scale = 0.34f;
        signals.eyeLeft = 1.0f;
        signals.eyeRight = 1.0f;
        signals.yaw = yaw;
        signals.pitch = pitch;
        signals.roll = roll;
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
    public void aTurnOfTheHeadTurnsTheModelAlmostAtOnce() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        // Сначала человек сидит спокойно: модель выходит из демонстрационной позы.
        run(mapper, head(0.0f, 0.0f, 0.0f), 1.0f);
        // Теперь поворот: спустя десятую долю секунды модель уже почти на месте.
        final Pose after = run(mapper, head(20.0f, 0.0f, 0.0f), 0.1f);
        assertTrue("через 0,1 с угол всего " + after.angleX + "°",
                Math.abs(after.angleX) > 20.0f);
    }

    @Test
    public void aTurnOfTheHeadIsBigEnoughToBeSeen() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final Pose pose = run(mapper, head(20.0f, 0.0f, 0.0f), 1.5f);
        assertTrue("поворот головы человека на 20° даёт всего " + pose.angleX + "°",
                Math.abs(pose.angleX) >= 27.0f);
    }

    @Test
    public void aNodIsPassedAsWell() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final Pose pose = run(mapper, head(0.0f, 15.0f, 0.0f), 1.5f);
        assertTrue("кивок почти не виден: " + pose.angleY, Math.abs(pose.angleY) > 15.0f);
    }

    @Test
    public void onlyTheRaisedHandMovesItsOwnArm() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final FaceSignals signals = head(0.0f, 0.0f, 0.0f);
        signals.handsSeen = true;
        signals.hands = 1;
        signals.fingers = 4;
        signals.handSeenRight = true;
        signals.handUpRight = 0.9f;
        signals.handOpenRight = 0.8f;
        signals.fingersRight = 4;
        final Pose pose = run(mapper, signals, 1.0f);
        // Зеркальная картинка: правая рука человека двигает левую руку персонажа.
        assertTrue("левая рука персонажа не поднялась: " + pose.armLeft, pose.armLeft > 0.3f);
        assertEquals("правая рука персонажа не должна двигаться", -1.0f, pose.armRight, 0.001f);
        assertTrue(pose.handSeenLeft);
        assertTrue(!pose.handSeenRight);
    }

    @Test
    public void bothHandsMoveBothArms() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final FaceSignals signals = head(0.0f, 0.0f, 0.0f);
        signals.handsSeen = true;
        signals.hands = 2;
        signals.fingers = 3;
        signals.handSeenLeft = true;
        signals.handSeenRight = true;
        signals.handUpLeft = 0.4f;
        signals.handUpRight = 0.8f;
        final Pose pose = run(mapper, signals, 1.0f);
        assertTrue(pose.armLeft > pose.armRight);
        assertTrue(pose.armRight > 0.1f);
    }

    @Test
    public void aHandThatLeftTheFrameReleasesTheArm() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final FaceSignals raised = head(0.0f, 0.0f, 0.0f);
        raised.handsSeen = true;
        raised.handSeenRight = true;
        raised.handUpRight = 0.9f;
        run(mapper, raised, 0.5f);
        // Рука ушла: сторона отпускается, и модель возвращается к позе анимации.
        final Pose after = run(mapper, head(0.0f, 0.0f, 0.0f), 0.3f);
        assertEquals(-1.0f, after.armLeft, 0.001f);
        assertEquals(-1.0f, after.armRight, 0.001f);
        assertTrue(!after.handsSeen);
    }

    @Test
    public void theChinGestureIsPassedPerHand() {
        final TrackingMapper mapper = new TrackingMapper();
        mapper.setAutoCalibration(false);
        final FaceSignals signals = head(0.0f, 0.0f, 0.0f);
        signals.handsSeen = true;
        signals.handSeenLeft = true;
        signals.chinTouchLeft = 0.9f;
        signals.fingersLeft = 1;
        final Pose pose = run(mapper, signals, 0.8f);
        // Левая рука человека двигает правую руку персонажа на зеркальной картинке.
        assertTrue("касание подбородка не дошло: " + pose.chinTouchRight, pose.chinTouchRight > 0.5f);
        assertTrue(pose.chinRight);
        assertTrue(!pose.chinLeft);
    }
}
