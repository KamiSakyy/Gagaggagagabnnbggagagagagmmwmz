package com.echidna.studio.anim;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Checks that the model never looks frozen when no show and no camera are running. */
public class IdleDirectorTest {

    private static final float STEP = 1.0f / 60.0f;

    private static final class Recorder implements Motions {
        final List<String> played = new ArrayList<String>();

        @Override
        public void play(String name, float fadeIn, int priority) {
            played.add(name);
        }
    }

    @Test
    public void idleStartsMotionsOverTime() {
        final IdleDirector director = new IdleDirector(ShowLibrary.idleMotions(), 7L);
        final Recorder recorder = new Recorder();
        final Pose pose = new Pose();
        float time = 0.0f;
        while (time < 40.0f) {
            director.update(STEP, pose, recorder, true);
            time += STEP;
        }
        assertTrue("за 40 секунд ожидания не запустилось ни одной анимации", recorder.played.size() >= 3);
        for (String name : recorder.played) {
            assertTrue("неизвестный мошен в пуле: " + name, ShowLibrary.idleMotions().contains(name));
        }
    }

    @Test
    public void idleNeverInterruptsARunningMotion() {
        final IdleDirector director = new IdleDirector(ShowLibrary.idleMotions(), 11L);
        final Recorder recorder = new Recorder();
        final Pose pose = new Pose();
        for (int i = 0; i < 600; i++) {
            director.update(STEP, pose, recorder, false);
        }
        assertTrue("ожидание не должно перебивать текущую анимацию", recorder.played.isEmpty());
    }

    @Test
    public void idlePoseMovesAndStaysInsideTheRanges() {
        final IdleDirector director = new IdleDirector(ShowLibrary.idleMotions(), 13L);
        final Pose pose = new Pose();
        float minAngleY = Float.MAX_VALUE;
        float maxAngleY = -Float.MAX_VALUE;
        for (int i = 0; i < 60 * 30; i++) {
            director.update(STEP, pose, null, true);
            minAngleY = Math.min(minAngleY, pose.angleY);
            maxAngleY = Math.max(maxAngleY, pose.angleY);
            assertTrue("глаза вне диапазона", pose.eyeBallX >= -0.6f && pose.eyeBallX <= 0.7f);
            assertTrue("угол вне диапазона", pose.angleX >= -31.0f && pose.angleX <= 31.0f);
            assertFalse("NaN в позе", Float.isNaN(pose.angleX + pose.bodyX + pose.eyeBallX));
            assertTrue("вес ожидания должен быть мягким", pose.weight > 0.0f && pose.weight <= 1.0f);
            assertTrue("ожидание не должно брать на себя моргание", pose.eyeWeight == 0.0f);
        }
        assertTrue("модель почти не двигалась", maxAngleY - minAngleY > 1.5f);
    }

    @Test
    public void twoDirectorsWithTheSameSeedBehaveTheSame() {
        final IdleDirector first = new IdleDirector(ShowLibrary.idleMotions(), 99L);
        final IdleDirector second = new IdleDirector(ShowLibrary.idleMotions(), 99L);
        final Pose poseA = new Pose();
        final Pose poseB = new Pose();
        for (int i = 0; i < 1200; i++) {
            first.update(STEP, poseA, null, true);
            second.update(STEP, poseB, null, true);
        }
        assertTrue(Math.abs(poseA.angleY - poseB.angleY) < 0.0001f);
    }

    @Test
    public void idleSurvivesAnEmptyPool() {
        final IdleDirector director = new IdleDirector(new ArrayList<String>(), 5L);
        final Pose pose = new Pose();
        for (int i = 0; i < 600; i++) {
            director.update(STEP, pose, null, true);
        }
        assertNotNull(pose);
    }
}
