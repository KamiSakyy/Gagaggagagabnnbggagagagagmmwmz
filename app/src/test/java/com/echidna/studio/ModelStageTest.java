package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.echidna.studio.anim.Pose;
import com.echidna.studio.anim.ShowLibrary;
import com.echidna.studio.track.FaceSignals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks the behaviour of the stage with a fake avatar: the shows start their motions, the camera
 * mode hands the pose over to the tracker, the microphone opens the mouth and the transitions
 * between the modes are smooth instead of abrupt.
 */
public class ModelStageTest {

    private static final float STEP = 1.0f / 60.0f;

    /** Avatar that records what was asked of it. */
    private static final class FakeAvatar implements AvatarBridge {
        final List<String> played = new ArrayList<String>();
        final List<String> known = new ArrayList<String>(Arrays.asList(
                "act_normal_w", "act_hohoemu", "act_egao", "act_egao02", "act_egao03", "act_tereru",
                "act_unazuku", "act_nedaru", "act_kouyou", "act_bikkuri", "act_odoroku", "act_kyoton",
                "face_normal_w", "face_talk_small", "face_talk_normal", "face_metozi", "face_cheek_on"));
        String current;
        boolean finished = true;

        @Override
        public boolean playMotion(String name, float fadeIn, int priority) {
            if (!known.contains(name)) {
                return false;
            }
            played.add(name);
            current = name;
            finished = false;
            return true;
        }

        @Override
        public boolean isMotionFinished() {
            return finished;
        }

        @Override
        public String currentMotionName() {
            return current;
        }

        @Override
        public List<String> motionNames() {
            return known;
        }

        @Override
        public boolean hasMotion(String name) {
            return known.contains(name);
        }
    }

    private static Pose run(ModelStage stage, float seconds) {
        final Pose pose = new Pose();
        final int frames = Math.round(seconds / STEP);
        for (int i = 0; i < frames; i++) {
            pose.set(stage.tick(STEP));
        }
        return pose;
    }

    @Test
    public void stageStartsInIdleAndPlaysMotionsOnItsOwn() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);
        assertEquals(ModelStage.Mode.IDLE, stage.mode());
        run(stage, 30.0f);
        assertFalse("ожидание должно играть анимации", avatar.played.isEmpty());
    }

    @Test
    public void everyShowStartsItsMotions() {
        for (com.echidna.studio.anim.Show show : ShowLibrary.shows()) {
            final FakeAvatar avatar = new FakeAvatar();
            final ModelStage stage = new ModelStage();
            stage.attach(avatar, null);
            stage.startShow(show.id);
            assertEquals(ModelStage.Mode.SHOW, stage.mode());
            run(stage, show.duration + 0.5f);
            assertFalse("показ " + show.id + " не запустил ни одной анимации", avatar.played.isEmpty());
            for (String name : avatar.played) {
                assertTrue("показ " + show.id + " запустил неизвестный мошен " + name,
                        avatar.known.contains(name));
            }
        }
    }

    @Test
    public void cameraModeHandsThePoseToTheTracker() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);
        stage.toCamera();

        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        signals.yaw = 22.0f;
        signals.pitch = 10.0f;
        signals.eyeLeft = 1.0f;
        signals.eyeRight = 1.0f;

        final Pose pose = new Pose();
        for (int i = 0; i < 90; i++) {
            stage.setSignals(signals);
            pose.set(stage.tick(STEP));
        }
        assertTrue("камера не повернула голову модели: " + pose.angleY, Math.abs(pose.angleY) > 10.0f);
        assertEquals(ModelStage.Mode.CAMERA, stage.mode());
    }

    @Test
    public void blinkingInCameraModeClosesTheEyes() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);
        stage.toCamera();

        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        signals.eyeLeft = 1.0f;
        signals.eyeRight = 1.0f;
        final Pose pose = new Pose();
        for (int i = 0; i < 60; i++) {
            stage.setSignals(signals);
            pose.set(stage.tick(STEP));
        }
        assertTrue(pose.eyeLOpen > 0.9f);

        signals.eyeLeft = 0.0f;
        signals.eyeRight = 0.0f;
        for (int i = 0; i < 6; i++) {
            stage.setSignals(signals);
            pose.set(stage.tick(STEP));
        }
        assertTrue("моргание не дошло до модели: " + pose.eyeLOpen, pose.eyeLOpen < 0.4f);
        assertTrue("вес глаз должен быть включён в режиме камеры", pose.eyeWeight > 0.9f);
    }

    @Test
    public void microphoneOpensTheMouth() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);
        stage.toCamera();
        stage.setMicEnabled(true);

        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        final Pose pose = new Pose();
        for (int i = 0; i < 30; i++) {
            stage.setSignals(signals);
            stage.setMicLevel(0.0f);
            pose.set(stage.tick(STEP));
        }
        final float closed = pose.mouthOpenY;

        for (int i = 0; i < 25; i++) {
            stage.setSignals(signals);
            stage.setMicLevel(0.85f);
            pose.set(stage.tick(STEP));
        }
        assertTrue("микрофон не открыл рот: " + closed + " -> " + pose.mouthOpenY,
                pose.mouthOpenY > closed + 0.1f && pose.mouthOpenY > 0.2f);
    }

    @Test
    public void manualMotionReturnsToIdleWhenItEnds() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);
        stage.playManualMotion("act_egao");
        assertEquals(ModelStage.Mode.MANUAL, stage.mode());
        run(stage, 0.5f);
        avatar.finished = true;
        run(stage, 1.0f);
        assertEquals("после конца анимации студия должна вернуться в ожидание",
                ModelStage.Mode.IDLE, stage.mode());
    }

    @Test
    public void unknownMotionIsIgnored() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);
        stage.playManualMotion("нет_такого");
        assertEquals(ModelStage.Mode.IDLE, stage.mode());
        assertTrue(avatar.played.isEmpty());
    }

    @Test
    public void transitionsAreSmoothAndNeverNaN() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);

        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        signals.yaw = 24.0f;

        Pose previous = run(stage, 1.0f);
        float biggestJump = 0.0f;
        final Pose pose = new Pose();
        // Walk through every mode change and make sure no frame teleports the model.
        final String[] actions = {"show", "camera", "idle", "manual"};
        for (String action : actions) {
            switch (action) {
                case "show":
                    stage.startShow(ShowLibrary.ID_DANCE);
                    break;
                case "camera":
                    stage.toCamera();
                    break;
                case "manual":
                    stage.playManualMotion("act_kouyou");
                    break;
                default:
                    stage.toIdle();
                    break;
            }
            for (int i = 0; i < 120; i++) {
                stage.setSignals(signals);
                pose.set(stage.tick(STEP));
                final float jump = Math.abs(pose.angleY - previous.angleY)
                        + Math.abs(pose.angleX - previous.angleX)
                        + Math.abs(pose.bodyX - previous.bodyX);
                biggestJump = Math.max(biggestJump, jump);
                assertFalse("NaN после перехода", Float.isNaN(pose.angleY + pose.angleX + pose.bodyX));
                previous = new Pose(pose);
            }
        }
        assertTrue("слишком резкий скачок при переходе: " + biggestJump, biggestJump < 3.0f);
    }

    @Test
    public void poseNeverLeavesTheModelRangesInAnyMode() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);
        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        final Pose pose = new Pose();

        for (int cycle = 0; cycle < 4; cycle++) {
            switch (cycle) {
                case 1:
                    stage.startShow(ShowLibrary.ID_DANCE);
                    break;
                case 2:
                    stage.toCamera();
                    break;
                default:
                    stage.toIdle();
                    break;
            }
            for (int i = 0; i < 60 * 25; i++) {
                signals.yaw = 30.0f * (float) Math.sin(i * 0.05f);
                signals.pitch = 25.0f * (float) Math.cos(i * 0.07f);
                signals.roll = 20.0f * (float) Math.sin(i * 0.03f);
                signals.smile = (float) Math.abs(Math.sin(i * 0.02f));
                signals.mouthOpen = (float) Math.abs(Math.cos(i * 0.04f));
                stage.setSignals(signals);
                stage.setMicLevel((float) Math.abs(Math.sin(i * 0.11f)));
                pose.set(stage.tick(STEP));

                assertTrue("угол Y вне диапазона: " + pose.angleY,
                        pose.angleY >= -31.0f && pose.angleY <= 31.0f);
                assertTrue("угол Z вне диапазона: " + pose.angleZ,
                        pose.angleZ >= -17.0f && pose.angleZ <= 13.0f);
                assertTrue("рот вне диапазона", pose.mouthOpenY >= 0.0f && pose.mouthOpenY <= 1.0f);
                assertTrue("глаза вне диапазона", pose.eyeLOpen >= 0.0f && pose.eyeLOpen <= 1.0f);
                assertFalse("NaN", Float.isNaN(pose.angleY + pose.angleZ + pose.mouthOpenY));
                assertNotNull(pose);
            }
        }
    }

    @Test
    public void stageKeepsRunningWithoutAnySignals() {
        final FakeAvatar avatar = new FakeAvatar();
        final ModelStage stage = new ModelStage();
        stage.attach(avatar, null);
        stage.toCamera();
        // No camera frames at all: the stage must fall back to the demo pose, not freeze or crash.
        final Pose pose = run(stage, 6.0f);
        assertFalse(Float.isNaN(pose.angleX));
        assertTrue("демо-режим должен оживить модель", pose.weight > 0.0f);
    }
}
