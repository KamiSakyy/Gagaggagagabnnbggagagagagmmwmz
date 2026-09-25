package com.echidna.studio.anim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Checks the playback engine: motion triggering, looping and pose interpolation. */
public class ShowPlayerTest {

    private static final float STEP = 1.0f / 60.0f;

    private static final class Recorder implements Motions {
        final List<String> played = new ArrayList<String>();

        @Override
        public void play(String name, float fadeIn, int priority) {
            played.add(name);
        }
    }

    @Test
    public void startsEveryMotionOfTheShowExactlyOncePerLoop() {
        final Show show = ShowLibrary.byId(ShowLibrary.ID_CUTE);
        final ShowPlayer player = new ShowPlayer();
        final Recorder recorder = new Recorder();
        final Pose pose = new Pose();
        player.play(show);

        int expected = 0;
        for (Segment segment : show.segments) {
            if (segment.motion != null) {
                expected++;
            }
        }
        assertTrue("в показе нет ни одного мошена", expected > 3);

        // Exactly one loop minus one frame: every motion of the show must have been asked for once.
        final int frames = (int) Math.floor(show.duration / STEP) - 1;
        for (int i = 0; i < frames; i++) {
            player.update(STEP, pose, recorder);
        }
        assertEquals("за один цикл каждый мошен должен запуститься ровно раз",
                expected, recorder.played.size());

        // And the next loop does it again.
        for (int i = 0; i < frames; i++) {
            player.update(STEP, pose, recorder);
        }
        assertEquals("второй цикл должен повторить все мошены",
                expected * 2, recorder.played.size());
    }

    @Test
    public void everyMotionOfAShowIsAskedForInTheRightOrder() {
        final Show show = ShowLibrary.byId(ShowLibrary.ID_CHARM);
        final ShowPlayer player = new ShowPlayer();
        final Recorder recorder = new Recorder();
        final Pose pose = new Pose();
        player.play(show);

        final int frames = (int) Math.floor(show.duration / STEP) - 1;
        for (int i = 0; i < frames; i++) {
            player.update(STEP, pose, recorder);
        }

        int index = 0;
        for (Segment segment : show.segments) {
            if (segment.motion != null) {
                assertEquals("мошены запускаются не по порядку сегментов",
                        segment.motion, recorder.played.get(index));
                index++;
            }
        }
    }

    @Test
    public void poseInterpolatesBetweenKeyframes() {
        final Show show = ShowLibrary.byId(ShowLibrary.ID_GREET);
        final ShowPlayer player = new ShowPlayer();
        final Pose pose = new Pose();
        player.play(show);

        final Segment first = show.segments.get(0);
        // Halfway through the first segment the pose has to sit between from and to.
        float time = 0.0f;
        while (time < first.start + first.duration() * 0.5f) {
            player.update(STEP, pose, null);
            time += STEP;
        }
        assertTrue("поза не двинулась", Math.abs(pose.angleX - first.from.angleX) > 0.001f
                || Math.abs(pose.angleY - first.from.angleY) > 0.001f);
        assertTrue("поза проскочила за целевую",
                Math.abs(pose.angleX - first.to.angleX) > Math.abs(first.from.angleX - first.to.angleX) * 0.5f
                        || Math.abs(pose.angleY - first.to.angleY)
                        <= Math.abs(first.from.angleY - first.to.angleY));
    }

    @Test
    public void showLoopsAndKeepsRunning() {
        final Show show = ShowLibrary.byId(ShowLibrary.ID_DANCE);
        final ShowPlayer player = new ShowPlayer();
        final Pose pose = new Pose();
        player.play(show);
        float time = 0.0f;
        while (time < show.duration * 2.5f) {
            assertTrue("танец обязан крутиться бесконечно", player.update(STEP, pose, null));
            time += STEP;
        }
        assertTrue("счётчик кругов не вырос", player.pass() >= 2);
        assertTrue(player.isActive());
    }

    @Test
    public void longFrameDoesNotSkipTheShow() {
        final Show show = ShowLibrary.byId(ShowLibrary.ID_CHARM);
        final ShowPlayer player = new ShowPlayer();
        final Pose pose = new Pose();
        player.play(show);
        // A frame after the app was in the background must be clipped, not fast forward the show.
        player.update(5.0f, pose, null);
        assertTrue("показ перескочил дальше допустимого", player.time() <= 0.26f);
    }

    @Test
    public void stopsWhenAskedTo() {
        final ShowPlayer player = new ShowPlayer();
        final Pose pose = new Pose();
        player.play(ShowLibrary.byId(ShowLibrary.ID_CUTE));
        player.update(STEP, pose, null);
        player.stop();
        assertFalse(player.isActive());
        assertFalse(player.update(STEP, pose, null));
    }

    @Test
    public void segmentAtFindsTheOwner() {
        final Show show = ShowLibrary.byId(ShowLibrary.ID_SURPRISE);
        final Segment first = show.segmentAt(0.0f);
        assertEquals(show.segments.get(0), first);
        final Segment last = show.segmentAt(show.duration - 0.01f);
        assertEquals(show.segments.get(show.segments.size() - 1), last);
    }

    @Test
    public void everyShowRunsForTwoFullLoopsWithoutNaN() {
        final Pose pose = new Pose();
        for (Show show : ShowLibrary.shows()) {
            final ShowPlayer player = new ShowPlayer();
            player.play(show);
            float time = 0.0f;
            while (time < show.duration * 2.0f) {
                player.update(STEP, pose, null);
                assertFalse(show.id + ": NaN в позе",
                        Float.isNaN(pose.angleX + pose.angleY + pose.angleZ
                                + pose.bodyX + pose.bodyY + pose.bodyZ
                                + pose.mouthOpenY + pose.mouthForm + pose.weight));
                time += STEP;
            }
        }
    }
}
