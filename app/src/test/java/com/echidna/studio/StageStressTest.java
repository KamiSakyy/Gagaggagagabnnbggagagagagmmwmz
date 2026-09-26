package com.echidna.studio;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;
import com.echidna.studio.anim.Show;
import com.echidna.studio.anim.ShowLibrary;
import com.echidna.studio.track.FaceSignals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Adversarial test of the whole behaviour layer.
 *
 * <p>Every value that comes from outside is unrealistic sometimes: a frame takes two seconds, the
 * tracker reports a NaN, the user's head turns by 400 degrees, the app is
 * switched between modes ten times per second. None of that may throw, and none of it may leave the
 * model with a broken pose - the unit tests around single features would not catch such a
 * combination, and on a phone it would show up as a crash or as a character that freezes.</p>
 */
public class StageStressTest {

    private static final float[] REALISTIC_STEPS = {
            1.0f / 60.0f, 1.0f / 30.0f, 1.0f / 120.0f, 0.02f, 0.05f, 0.2f, 1.5f, 0.0001f
    };

    private static final float[] WILD_VALUES = {
            0.0f, 1.0f, -1.0f, 45.0f, -180.0f, 90.0f, Float.NaN, Float.POSITIVE_INFINITY,
            1e-6f, 1e6f, 0.5f
    };

    private static final class FakeAvatar implements AvatarBridge {
        final List<String> known = new ArrayList<String>(Arrays.asList(
                "act_normal_w", "act_hohoemu", "act_egao", "act_egao02", "act_egao03", "act_tereru",
                "act_unazuku", "act_nedaru", "act_kouyou", "act_bikkuri", "act_odoroku", "act_kyoton",
                "act_sumashi", "act_tameiki", "act_ikaru", "act_konwaku", "act_nayamu", "act_hohoemu_w",
                "face_normal_w", "face_talk_small", "face_talk_normal", "face_talk_large", "face_metozi",
                "face_cheek_on", "face_cheek_off", "face_shinken_w", "add_select_L", "add_select_C"));
        final List<String> played = new ArrayList<String>();
        final List<String> expressions = new ArrayList<String>(Arrays.asList("Happy1", "Wink", "Shy"));
        String current;
        String lastExpression;
        boolean finished = true;

        @Override
        public List<String> expressionNames() {
            return expressions;
        }

        @Override
        public boolean playExpression(String name) {
            if (expressions.contains(name)) {
                lastExpression = name;
                return true;
            }
            return false;
        }

        @Override
        public void stopMotions() {
        }

        public void stopExpression() {
            lastExpression = null;
        }

        @Override
        public boolean playMotion(String name, float fadeIn, int priority) {
            if (name == null || !known.contains(name)) {
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
            return name != null && known.contains(name);
        }
    }

    private static FaceSignals signals(Random random, boolean broken) {
        final FaceSignals signals = new FaceSignals();
        signals.found = random.nextInt(10) > 1;          // the face disappears now and then
        if (broken) {
            signals.yaw = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.pitch = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.roll = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.eyeLeft = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.eyeRight = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.mouthOpen = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.smile = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.centerX = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.centerY = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
            signals.scale = WILD_VALUES[random.nextInt(WILD_VALUES.length)];
        } else {
            signals.yaw = random.nextFloat() * 60.0f - 30.0f;
            signals.pitch = random.nextFloat() * 40.0f - 20.0f;
            signals.roll = random.nextFloat() * 30.0f - 15.0f;
            signals.eyeLeft = random.nextFloat();
            signals.eyeRight = random.nextFloat();
            signals.mouthOpen = random.nextFloat();
            signals.smile = random.nextFloat();
            signals.centerX = random.nextFloat() * 2.0f - 1.0f;
            signals.centerY = random.nextFloat() * 2.0f - 1.0f;
            signals.scale = 0.2f + random.nextFloat() * 0.4f;
        }
        signals.timeMs = random.nextInt(100000);
        return signals;
    }

    private static void checkPose(Pose pose, String context) {
        assertNotNull(context + ": пустая поза", pose);
        final float[] values = {
                pose.angleX, pose.angleY, pose.angleZ, pose.bodyX, pose.bodyY, pose.bodyZ,
                pose.eyeLOpen, pose.eyeROpen, pose.eyeLSmile, pose.eyeRSmile,
                pose.eyeBallX, pose.eyeBallY, pose.mouthOpenY, pose.mouthForm,
                pose.browLY, pose.browRY, pose.cheek, pose.weight, pose.eyeWeight,
                pose.offsetX, pose.offsetY, pose.zoom
        };
        for (float value : values) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                fail(context + ": поза получила " + value);
            }
        }
        assertTrue(context + ": голова ушла за пределы модели",
                Math.abs(pose.angleX) <= ParamLimits.ANGLE_X_MAX + 0.001f
                        && Math.abs(pose.angleY) <= ParamLimits.ANGLE_Y_MAX + 0.001f);
        assertTrue(context + ": сдвиг модели за пределами",
                Math.abs(pose.offsetX) <= ParamLimits.OFFSET_MAX + 0.001f
                        && Math.abs(pose.offsetY) <= ParamLimits.OFFSET_MAX + 0.001f);
        assertTrue(context + ": масштаб за пределами",
                pose.zoom >= ParamLimits.ZOOM_MIN - 0.001f
                        && pose.zoom <= ParamLimits.ZOOM_MAX + 0.001f);
        assertTrue(context + ": вес позы не в диапазоне: " + pose.weight,
                pose.weight >= -0.001f && pose.weight <= 1.001f);
    }

    @Test
    public void chaoticFramesNeverBreakTheStage() {
        final Random random = new Random(20260926L);
        final ModelStage stage = new ModelStage();
        final FakeAvatar avatar = new FakeAvatar();
        stage.attach(avatar, null);

        final List<Show> shows = ShowLibrary.shows();
        for (int frame = 0; frame < 20000; frame++) {
            final float dt = REALISTIC_STEPS[random.nextInt(REALISTIC_STEPS.length)];

            // Everything the interface can do, at a random moment.
            switch (random.nextInt(24)) {
                case 0: stage.toIdle(); break;
                case 1: stage.toCamera(); break;
                case 2: stage.startShow(shows.get(random.nextInt(shows.size())).id); break;
                case 3: stage.startShow("выдуманное-шоу"); break;
                case 4: stage.playManualMotion("act_egao"); break;
                case 5: stage.playManualMotion("нет-такого-мошена"); break;
                case 6: stage.playManualMotion(null); break;
                case 7: stage.randomMotion(); break;
                default: break;
            }

            if (random.nextInt(3) == 0) {
                stage.setSignals(signals(random, random.nextInt(4) == 0));
            }
            if (random.nextInt(11) == 0) {
                avatar.finished = true;
            }

            final Pose pose = stage.tick(dt);
            checkPose(pose, "кадр " + frame + ", режим " + stage.mode());
        }

        assertTrue("сцена не запросила ни одной анимации", avatar.played.size() > 20);
    }

    private static float broken(Random random) {
        switch (random.nextInt(6)) {
            case 0: return Float.NaN;
            case 1: return -1.0f;
            case 2: return 1e9f;
            case 3: return 0.0f;
            case 4: return Float.NEGATIVE_INFINITY;
            default: return random.nextFloat();
        }
    }

    @Test
    public void everyShowSurvivesBeingInterruptedAtEveryMoment() {
        final List<Show> shows = ShowLibrary.shows();
        for (Show show : shows) {
            final ModelStage stage = new ModelStage();
            final FakeAvatar avatar = new FakeAvatar();
            stage.attach(avatar, null);
            stage.startShow(show.id);

            // Walk the whole timeline, interrupting with a mode switch at every second frame.
            final int frames = (int) (show.duration / (1.0f / 60.0f)) * 2 + 120;
            for (int frame = 0; frame < frames; frame++) {
                if (frame % 2 == 0) {
                    switch (frame % 6) {
                        case 0: stage.toCamera(); break;
                        case 2: stage.toIdle(); break;
                        case 4: stage.startShow(show.id); break;
                        default: break;
                    }
                }
                stage.setSignals(signals(new Random(frame), false));
                checkPose(stage.tick(1.0f / 60.0f), show.id + ", кадр " + frame);
            }
            assertTrue(show.id + ": шоу ничего не сыграло", avatar.played.size() > 0);
        }
    }

    @Test
    public void cameraModeWorksWithoutAnySignalsAtAll() {
        final ModelStage stage = new ModelStage();
        final FakeAvatar avatar = new FakeAvatar();
        stage.attach(avatar, null);
        stage.toCamera();

        // Прибор, который вообще ничего не сообщает: сцена не падает, не выдаёт NaN и не начинает
        // играть за человека. Модель при этом спокойна - вес позы уходит в ноль.
        final Pose first = stage.tick(1.0f / 60.0f);
        final float firstAngle = first.angleY;
        for (int frame = 0; frame < 1200; frame++) {
            checkPose(stage.tick(1.0f / 60.0f), "тишина, кадр " + frame);
        }
        final Pose later = stage.tick(1.0f / 60.0f);
        assertTrue("без сигналов модель должна успокоиться: " + later.weight, later.weight < 0.2f);
        assertTrue("поза не должна уезжать сама: " + Math.abs(later.angleY - firstAngle),
                Math.abs(later.angleY - firstAngle) < 2.0f);
    }

    @Test
    public void aLostFaceIsAlwaysRecovered() {
        final ModelStage stage = new ModelStage();
        final FakeAvatar avatar = new FakeAvatar();
        stage.attach(avatar, null);
        stage.toCamera();

        final FaceSignals live = signals(new Random(7L), false);
        live.found = true;
        final FaceSignals lost = new FaceSignals();
        lost.found = false;

        for (int cycle = 0; cycle < 40; cycle++) {
            for (int frame = 0; frame < 30; frame++) {
                stage.setSignals(live);
                checkPose(stage.tick(1.0f / 60.0f), "лицо найдено, цикл " + cycle);
            }
            for (int frame = 0; frame < 120; frame++) {
                stage.setSignals(lost);
                checkPose(stage.tick(1.0f / 60.0f), "лицо потеряно, цикл " + cycle);
            }
        }
        assertTrue(true);
    }
}
