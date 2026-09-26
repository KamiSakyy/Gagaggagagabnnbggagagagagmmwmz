package com.echidna.studio.anim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Проверяет, что шоу оживают и на персонажах без файлов движений: название движения переводится в
 * ближайшее выражение лица.
 */
public class ExpressionPickerTest {

    /** Пресеты, которые несёт объёмная модель из репозитория three-vrm. */
    private static final List<String> VRM = Arrays.asList("aa", "angry", "blink", "blinkLeft",
            "blinkRight", "ee", "happy", "ih", "lookDown", "lookLeft", "lookRight", "lookUp",
            "neutral", "oh", "ou", "relaxed", "sad", "surprised");

    @Test
    public void happyMotionsBecomeTheHappyFace() {
        assertEquals("happy", ExpressionPicker.resolve(VRM, "act_egao02"));
        assertEquals("happy", ExpressionPicker.resolve(VRM, "face_hohoemu"));
        assertEquals("happy", ExpressionPicker.resolve(VRM, "smile_wide"));
    }

    @Test
    public void angrySadAndSurprisedMoodsAreRecognised() {
        assertEquals("angry", ExpressionPicker.resolve(VRM, "face_ikaru"));
        assertEquals("sad", ExpressionPicker.resolve(VRM, "face_nayamu"));
        assertEquals("sad", ExpressionPicker.resolve(VRM, "act_tameiki"));
        assertEquals("surprised", ExpressionPicker.resolve(VRM, "face_bikkuri"));
    }

    @Test
    public void talkAndShyMoodsUseFacesTheModelReallyHas() {
        assertEquals("aa", ExpressionPicker.resolve(VRM, "act_kouyou"));
        assertEquals("relaxed", ExpressionPicker.resolve(VRM, "act_tereru"));
    }

    @Test
    public void anUnknownMotionStillGetsALivingFace() {
        final String expression = ExpressionPicker.resolve(VRM, "motion_from_another_game");
        assertNotNull("у модели с мимикой всегда должно что-то найтись", expression);
        assertTrue("для объёмной модели нужен один из мягких пресетов: " + expression,
                expression.equals("happy") || expression.equals("relaxed")
                        || expression.equals("neutral"));
    }

    @Test
    public void aRigWithItsOwnNamesGetsItsOwnExpression() {
        final List<String> rig = Arrays.asList("exp_01", "exp_02", "exp_03");
        assertEquals("первое выражение рига", "exp_01", ExpressionPicker.resolve(rig, "act_egao"));
    }

    @Test
    public void aRigWithoutMoodExpressionsSkipsGazeAndEyelids() {
        final List<String> rig = new ArrayList<String>(
                Arrays.asList("blink", "lookUp", "lookDown", "eyesClosed", "smile01"));
        assertEquals("smile01", ExpressionPicker.resolve(rig, "act_bikkuri"));
    }

    @Test
    public void noExpressionsAtAllResolvesToNothing() {
        assertNull(ExpressionPicker.resolve(null, "act_egao"));
        assertNull(ExpressionPicker.resolve(new ArrayList<String>(), "act_egao"));
        assertNull("одни веки и взгляд - играть нечем",
                ExpressionPicker.resolve(Arrays.asList("blink", "lookLeft"), "act_egao"));
    }

    @Test
    public void anEmptyRequestDoesNotCrash() {
        assertNotNull(ExpressionPicker.resolve(VRM, null));
        assertNotNull(ExpressionPicker.resolve(VRM, ""));
        assertNotNull(ExpressionPicker.resolve(VRM, "   "));
    }

    @Test
    public void caseAndFoldersDoNotMatter() {
        final List<String> mixed = Arrays.asList("Happy", "ANGRY", "Neutral");
        assertEquals("Happy", ExpressionPicker.resolve(mixed, "/motions/act_egao.motion3.json"));
        assertEquals("ANGRY", ExpressionPicker.resolve(mixed, "FACE_IKARU"));
    }
}
