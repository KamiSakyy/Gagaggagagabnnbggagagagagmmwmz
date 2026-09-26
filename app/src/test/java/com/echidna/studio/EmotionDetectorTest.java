package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.echidna.studio.track.EmotionDetector;
import com.echidna.studio.track.FaceSignals;

import org.junit.Test;

/**
 * Понимание эмоций по лицу.
 *
 * <p>Каждая эмоция собирается из нескольких мышц лица, поэтому тест подаёт именно тот набор
 * коэффициентов, который бывает у человека: улыбка со щеками, вскинутые брови с открытым ртом,
 * сведённые брови со сжатыми губами. Тест закрепляет и защиту от дрожания: эмоция не должна
 * меняться на каждом кадре.</p>
 */
public class EmotionDetectorTest {

    private static FaceSignals neutral() {
        final FaceSignals s = new FaceSignals();
        s.found = true;
        s.blendshapes = true;
        s.eyeLeft = 1.0f;
        s.eyeRight = 1.0f;
        s.roll = 0.0f;
        return s;
    }

    private static FaceSignals smile() {
        final FaceSignals s = neutral();
        s.blendMouthSmileLeft = 0.85f;
        s.blendMouthSmileRight = 0.85f;
        s.blendCheekSquintLeft = 0.7f;
        s.blendCheekSquintRight = 0.7f;
        s.blendEyeSquintLeft = 0.5f;
        s.blendEyeSquintRight = 0.5f;
        s.smile = 0.85f;
        return s;
    }

    private static FaceSignals surprised() {
        final FaceSignals s = neutral();
        s.blendBrowInnerUp = 0.9f;
        s.blendBrowOuterUpLeft = 0.8f;
        s.blendBrowOuterUpRight = 0.8f;
        s.blendEyeWideLeft = 0.9f;
        s.blendEyeWideRight = 0.9f;
        s.blendJawOpen = 0.8f;
        s.mouthOpen = 0.8f;
        return s;
    }

    private static FaceSignals angry() {
        final FaceSignals s = neutral();
        s.blendBrowDownLeft = 0.9f;
        s.blendBrowDownRight = 0.9f;
        s.blendMouthPressLeft = 0.8f;
        s.blendMouthPressRight = 0.8f;
        s.blendMouthFrownLeft = 0.7f;
        s.blendMouthFrownRight = 0.7f;
        s.blendNoseSneerLeft = 0.6f;
        s.blendNoseSneerRight = 0.6f;
        return s;
    }

    private static FaceSignals sad() {
        final FaceSignals s = neutral();
        s.blendBrowInnerUp = 0.85f;
        s.blendMouthFrownLeft = 0.7f;
        s.blendMouthFrownRight = 0.7f;
        s.blendMouthLowerDownLeft = 0.6f;
        s.blendMouthLowerDownRight = 0.6f;
        s.blendEyeLookDownLeft = 0.7f;
        s.blendEyeLookDownRight = 0.7f;
        s.eyeLeft = 0.85f;
        s.eyeRight = 0.85f;
        return s;
    }

    private static FaceSignals tired() {
        final FaceSignals s = neutral();
        s.eyeLeft = 0.25f;
        s.eyeRight = 0.28f;
        s.blendJawOpen = 0.5f;
        return s;
    }

    /** Кормит детектор одним и тем же лицом заданное время. */
    private static void feed(EmotionDetector detector, FaceSignals signals, float seconds) {
        final int frames = Math.round(seconds * 60.0f);
        for (int i = 0; i < frames; i++) {
            detector.update(signals, 1.0f / 60.0f);
        }
    }

    @Test
    public void aSmilingFaceReadsAsJoy() {
        final EmotionDetector detector = new EmotionDetector();
        feed(detector, smile(), 1.0f);
        assertEquals("улыбка должна читаться как радость",
                EmotionDetector.JOY, detector.emotion());
        assertTrue("сила радости должна быть заметной", detector.intensity() > 0.5f);
        assertEquals("радость", detector.name());
    }

    @Test
    public void raisedBrowsAndAnOpenMouthReadAsSurprise() {
        final EmotionDetector detector = new EmotionDetector();
        feed(detector, surprised(), 1.0f);
        assertEquals(EmotionDetector.SURPRISE, detector.emotion());
    }

    @Test
    public void knittedBrowsAndPressedLipsReadAsAnger() {
        final EmotionDetector detector = new EmotionDetector();
        feed(detector, angry(), 1.0f);
        assertEquals(EmotionDetector.ANGER, detector.emotion());
        assertTrue("злость должна быть видна по силе", detector.anger() > 0.4f);
    }

    @Test
    public void innerBrowsUpAndDowncastMouthReadAsSadness() {
        final EmotionDetector detector = new EmotionDetector();
        feed(detector, sad(), 1.0f);
        assertEquals(EmotionDetector.SADNESS, detector.emotion());
        assertTrue(detector.sadness() > 0.4f);
    }

    @Test
    public void halfClosedEyesAndAYawnReadAsTired() {
        final EmotionDetector detector = new EmotionDetector();
        feed(detector, tired(), 1.0f);
        assertEquals(EmotionDetector.TIRED, detector.emotion());
    }

    @Test
    public void aRelaxedFaceStaysNeutral() {
        final EmotionDetector detector = new EmotionDetector();
        feed(detector, neutral(), 2.0f);
        assertEquals(EmotionDetector.NEUTRAL, detector.emotion());
        assertFalse("спокойное лицо не должно считаться выразительным", detector.expressive());
        assertEquals("спокойствие", detector.name());
    }

    @Test
    public void aBriefTwitchDoesNotChangeTheEmotion() {
        final EmotionDetector detector = new EmotionDetector();
        feed(detector, smile(), 1.0f);
        assertEquals(EmotionDetector.JOY, detector.emotion());
        // Один кадр злости - это дрожь мышц, а не смена настроения.
        detector.update(angry(), 1.0f / 60.0f);
        assertEquals("одного кадра мало для смены эмоции",
                EmotionDetector.JOY, detector.emotion());
    }

    @Test
    public void aSustainedFrownDoesTakeOverFromSmile() {
        final EmotionDetector detector = new EmotionDetector();
        feed(detector, smile(), 1.0f);
        assertEquals(EmotionDetector.JOY, detector.emotion());
        feed(detector, angry(), 1.5f);
        assertEquals("устойчивое выражение должно победить", EmotionDetector.ANGER, detector.emotion());
    }

    @Test
    public void mlKitSignalsWithoutBlendshapesStillGiveJoy() {
        final EmotionDetector detector = new EmotionDetector();
        final FaceSignals s = new FaceSignals();
        s.found = true;
        s.blendshapes = false;
        s.smile = 0.8f;
        s.eyeLeft = 1.0f;
        s.eyeRight = 1.0f;
        feed(detector, s, 1.0f);
        assertEquals(EmotionDetector.JOY, detector.emotion());
    }

    /** Слабое, но настоящее выражение тоже должно распознаваться: сеть часто занижает оценки. */
    @Test
    public void aWeakSmileIsStillReadAsJoy() {
        final EmotionDetector detector = new EmotionDetector();
        final FaceSignals s = neutral();
        s.blendMouthSmileLeft = 0.35f;
        s.blendMouthSmileRight = 0.35f;
        s.blendCheekSquintLeft = 0.3f;
        s.blendCheekSquintRight = 0.3f;
        s.smile = 0.35f;
        feed(detector, s, 1.5f);
        assertEquals("даже сдержанную улыбку видно", EmotionDetector.JOY, detector.emotion());
    }

    /** Улыбка, измеренная по точкам лица, работает и без коэффициентов мимики. */
    @Test
    public void aGeometricSmileCountsAsJoy() {
        final EmotionDetector detector = new EmotionDetector();
        final FaceSignals s = neutral();
        s.geometric = true;
        s.smileGeo = 0.55f;
        s.eyeOpenGeo = 1.0f;
        s.blendMouthSmileLeft = 0.1f;
        s.blendMouthSmileRight = 0.1f;
        feed(detector, s, 1.2f);
        assertEquals(EmotionDetector.JOY, detector.emotion());
    }

    /** Вскинутые брови, измеренные по точкам, дают удивление даже при слабых коэффициентах. */
    @Test
    public void geometricBrowsGiveSurprise() {
        final EmotionDetector detector = new EmotionDetector();
        final FaceSignals s = neutral();
        s.geometric = true;
        s.browGeo = 0.9f;
        s.mouthOpenGeo = 0.7f;
        s.blendBrowInnerUp = 0.2f;
        feed(detector, s, 1.2f);
        assertEquals(EmotionDetector.SURPRISE, detector.emotion());
    }

    @Test
    public void everyEmotionHasARussianName() {
        for (int i = 0; i < EmotionDetector.COUNT; i++) {
            final String name = EmotionDetector.name(i);
            assertTrue("пустое имя для эмоции " + i, name != null && !name.isEmpty());
            assertTrue("имя должно быть русским: " + name,
                    name.charAt(0) >= 'А' && name.charAt(0) <= 'я');
        }
        assertEquals("восторг", EmotionDetector.name(EmotionDetector.DELIGHT));
    }
}
