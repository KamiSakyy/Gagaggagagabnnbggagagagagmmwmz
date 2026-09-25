package com.echidna.studio.anim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the authored shows: five of them, a continuous timeline, plausible values and motions that
 * really exist in the model.
 */
public class ShowLibraryTest {

    /** Every motion file that ships inside Echidna.model3.json. */
    private static final String[] REAL_MOTIONS = {
            "act_normal_w", "act_hohoemu", "act_egao", "act_egao02", "act_egao03", "act_tereru",
            "act_unazuku", "act_nedaru", "act_kouyou", "act_bikkuri", "act_odoroku", "act_kyoton",
            "act_nayamu", "act_tameiki", "act_sumashi", "act_konwaku", "act_ikaru", "act_tereru_w",
            "face_normal_w", "face_talk_small", "face_talk_normal", "face_talk_large",
            "face_metozi", "face_cheek_on", "face_cheek_off", "face_shinken_w"
    };

    @Test
    public void libraryHasFiveShows() {
        final List<Show> shows = ShowLibrary.shows();
        assertEquals(5, shows.size());
        assertEquals(ShowLibrary.ID_CUTE, shows.get(0).id);
        assertEquals(ShowLibrary.ID_DANCE, shows.get(1).id);
        assertEquals(ShowLibrary.ID_CHARM, shows.get(2).id);
        assertEquals(ShowLibrary.ID_GREET, shows.get(3).id);
        assertEquals(ShowLibrary.ID_SURPRISE, shows.get(4).id);
    }

    @Test
    public void everyShowLooksUpById() {
        for (Show show : ShowLibrary.shows()) {
            assertNotNull(ShowLibrary.byId(show.id));
            assertNotNull(show.title);
            assertNotNull(show.emoji);
            assertNotNull(show.blurb);
            assertTrue(show.loop);
            assertTrue("показ " + show.id + " слишком короткий", show.duration >= 8.0f);
            assertTrue("показ " + show.id + " подозрительно длинный", show.duration <= 20.0f);
        }
    }

    @Test
    public void timelineIsContinuousAndOrdered() {
        for (Show show : ShowLibrary.shows()) {
            float cursor = 0.0f;
            for (Segment segment : show.segments) {
                assertEquals("сегмент " + show.id + " начинается не там, где кончился предыдущий",
                        cursor, segment.start, 0.0001f);
                assertTrue("сегмент " + show.id + " идёт назад", segment.end > segment.start);
                assertNotNull(segment.from);
                assertNotNull(segment.to);
                assertNotNull(segment.ease);
                cursor = segment.end;
            }
            assertEquals("длительность " + show.id + " не совпадает с последним сегментом",
                    show.duration, cursor, 0.0001f);
        }
    }

    @Test
    public void everyMotionOfEveryShowExistsInTheModel() {
        final List<String> real = new ArrayList<String>();
        for (String name : REAL_MOTIONS) {
            real.add(name);
        }
        for (Show show : ShowLibrary.shows()) {
            for (Segment segment : show.segments) {
                if (segment.motion != null) {
                    assertTrue("в показе " + show.id + " указан несуществующий мошен " + segment.motion,
                            real.contains(segment.motion));
                }
            }
        }
    }

    @Test
    public void idlePoolMotionsExistInTheModel() {
        final List<String> real = new ArrayList<String>();
        for (String name : REAL_MOTIONS) {
            real.add(name);
        }
        for (String name : ShowLibrary.idleMotions()) {
            assertTrue("в пуле ожидания несуществующий мошен " + name, real.contains(name));
        }
        for (String name : ShowLibrary.cameraIdleMotions()) {
            assertTrue("в пуле камеры несуществующий мошен " + name, real.contains(name));
        }
    }

    @Test
    public void danceShowKeepsTheRhythm() {
        final Show dance = ShowLibrary.byId(ShowLibrary.ID_DANCE);
        final int segments = dance.segments.size();
        assertTrue("танец собран не из тактов", segments >= 40);

        // The body has to swing several times per loop, and with an amplitude worth watching.
        int signChanges = 0;
        float previous = 0.0f;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (Segment segment : dance.segments) {
            final float value = segment.to.bodyX;
            if (previous != 0.0f && Math.signum(value) != Math.signum(previous)) {
                signChanges++;
            }
            previous = value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        assertTrue("корпус танцует слишком вяло: " + signChanges + " смен направления", signChanges >= 8);
        assertTrue("амплитуда корпуса слишком мала: " + (max - min), max - min >= 6.0f);

        // And the head has to move at all four measured axes of the choreography.
        float headMin = Float.MAX_VALUE;
        float headMax = -Float.MAX_VALUE;
        for (Segment segment : dance.segments) {
            headMin = Math.min(headMin, segment.to.angleY);
            headMax = Math.max(headMax, segment.to.angleY);
        }
        assertTrue("голова почти не двигается в танце: " + (headMax - headMin), headMax - headMin > 8.0f);
    }

    @Test
    public void surpriseShowContainsAForcedMotion() {
        final Show surprise = ShowLibrary.byId(ShowLibrary.ID_SURPRISE);
        boolean forced = false;
        for (Segment segment : surprise.segments) {
            if (segment.priority == Motions.FORCE) {
                forced = true;
                break;
            }
        }
        assertTrue("сюрприз должен перебивать текущую анимацию", forced);
    }

    @Test
    public void charmShowLowersTheLids() {
        final Show charm = ShowLibrary.byId(ShowLibrary.ID_CHARM);
        boolean lowered = false;
        for (Segment segment : charm.segments) {
            if (segment.to.eyeLOpen < 0.75f || segment.from.eyeLOpen < 0.75f) {
                lowered = true;
                break;
            }
        }
        assertTrue("в показе «Соблазнение» должны быть прикрытые ресницы", lowered);
    }

    @Test
    public void cuteShowWinks() {
        final Show cute = ShowLibrary.byId(ShowLibrary.ID_CUTE);
        boolean wink = false;
        for (Segment segment : cute.segments) {
            if (segment.to.eyeROpen < 0.2f && segment.to.eyeLOpen > 0.9f) {
                wink = true;
                break;
            }
        }
        assertTrue("в показе «Милота» должно быть подмигивание", wink);
    }

    @Test
    public void posesStayInsideTheModelRanges() {
        for (Show show : ShowLibrary.shows()) {
            for (Segment segment : show.segments) {
                checkPose(show.id, segment.from);
                checkPose(show.id, segment.to);
            }
        }
    }

    private static void checkPose(String show, Pose pose) {
        assertTrue(show + ": угол X вне диапазона", pose.angleX >= -30.5f && pose.angleX <= 30.5f);
        assertTrue(show + ": угол Y вне диапазона", pose.angleY >= -30.5f && pose.angleY <= 30.5f);
        assertTrue(show + ": угол Z вне диапазона", pose.angleZ >= -16.5f && pose.angleZ <= 12.5f);
        assertTrue(show + ": наклон корпуса вне диапазона", pose.bodyX >= -10.5f && pose.bodyX <= 10.5f);
        assertTrue(show + ": открытие рта вне диапазона",
                pose.mouthOpenY >= 0.0f && pose.mouthOpenY <= 1.0f);
        assertTrue(show + ": форма рта вне диапазона", pose.mouthForm >= -1.0f && pose.mouthForm <= 1.0f);
        assertTrue(show + ": открытие глаз вне диапазона",
                pose.eyeLOpen >= 0.0f && pose.eyeLOpen <= 1.0f);
        assertTrue(show + ": вес вне диапазона", pose.weight >= 0.0f && pose.weight <= 1.0f);
        assertFalse(show + ": NaN в позе", Float.isNaN(pose.angleX + pose.angleY + pose.angleZ
                + pose.bodyX + pose.mouthOpenY + pose.eyeLOpen));
    }
}
