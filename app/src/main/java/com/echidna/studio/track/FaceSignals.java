package com.echidna.studio.track;

/**
 * One analysed camera frame, already normalised into a coordinate system the mapper can use.
 *
 * <p>The fields come in two flavours. The head pose ({@link #yaw}, {@link #pitch}, {@link #roll}) is
 * available from every tracker. The eleven blendshape channels are only filled in by the MediaPipe
 * tracker; when they are absent {@link #blendshapes} stays {@code false} and the mapper falls back
 * to the coarser probabilities of ML Kit. Nothing here depends on Android, so the whole mapping
 * layer stays testable on the JVM.</p>
 */
public final class FaceSignals {
    /** Whether a face was seen in the analysed frame. */
    public boolean found;

    /** Head rotation in degrees: yaw (turn), pitch (nod), roll (tilt). */
    public float yaw;
    public float pitch;
    public float roll;

    /** Eye openness 0..1 (as reported for the subject's own left and right eye). */
    public float eyeLeft = 1.0f;
    public float eyeRight = 1.0f;

    /** Smile probability 0..1. */
    public float smile;

    /** Mouth opening 0..1. */
    public float mouthOpen;

    /** Face centre inside the frame: -1 is the left edge, +1 the right edge. */
    public float centerX;
    public float centerY;

    /** Face height relative to the frame height. */
    public float scale;

    /**
     * Положение лица на кадре: у человека глаза выше рта.
     *
     * <p>Единственный надёжный признак того, что кадр приходит вверх ногами: по нему приложение
     * само доворачивает картинку, каким бы ни был угол сенсора.</p>
     */
    public boolean faceUpright;
    /** Известно ли положение лица: у трекера без точек лица оно неизвестно. */
    public boolean faceUprightKnown;
    /**
     * Геометрия лица по 478 точкам: мимика, измеренная линейкой, а не нейросетью.
     *
     * <p>Улыбка - это поднятые уголки рта, удивление - поднятые брови, моргание - сомкнутые веки.
     * Эти величины считаются прямо по точкам лица и не зависят от того, насколько уверенно модель
     * распознала движение мышцы: там, где коэффициенты мимики молчат, геометрия всё видит.</p>
     */
    public boolean geometric;
    /** Уголки рта относительно середины губ: плюс - улыбка, минус - опущенные губы. */
    public float smileGeo;
    /** Раскрытие рта по точкам губ, 0..1. */
    public float mouthOpenGeo;
    /** Поднятие бровей над глазами: плюс - вскинуты, минус - сведены. */
    public float browGeo;
    /** Раскрытость век по точкам глаз, 0..1: моргание читается даже при слабых коэффициентах. */
    public float eyeOpenGeo;

    /** True when {@link #blendEyeBlinkLeft} and friends hold real data. */
    public boolean blendshapes;

    public float blendEyeBlinkLeft;
    public float blendEyeBlinkRight;
    public float blendJawOpen;
    public float blendMouthSmileLeft;
    public float blendMouthSmileRight;
    public float blendMouthPucker;
    public float blendBrowInnerUp;
    public float blendBrowDownLeft;
    public float blendBrowDownRight;
    public float blendCheekPuff;
    public float blendEyeSquintLeft;
    public float blendEyeSquintRight;
    public float blendMouthFrownLeft;
    public float blendMouthFrownRight;
    public float blendTongueOut;
    /**
     * Остальные каналы мимики из 52 коэффициентов MediaPipe.
     *
     * <p>Каждый коэффициент - это движение одной мышцы лица: внутренние и внешние концы бровей,
     * прищур, широко раскрытые глаза, нахмуренный нос, сжатые губы, опущенные уголки рта, взгляд
     * в сторону. Раньше из полусотни каналов читалось четырнадцать, и лицо модели двигалось
     * только крупными мазками. Из этих значений собираются эмоции - радость, удивление, злость,
     * грусть, смущение, задумчивость, - и каждая из них уходит в свои параметры модели.</p>
     */
    public float blendBrowOuterUpLeft;
    public float blendBrowOuterUpRight;
    public float blendCheekSquintLeft;
    public float blendCheekSquintRight;
    public float blendNoseSneerLeft;
    public float blendNoseSneerRight;
    public float blendEyeWideLeft;
    public float blendEyeWideRight;
    public float blendMouthPressLeft;
    public float blendMouthPressRight;
    public float blendMouthShrugUpper;
    public float blendMouthShrugLower;
    public float blendMouthStretchLeft;
    public float blendMouthStretchRight;
    public float blendMouthUpperUpLeft;
    public float blendMouthUpperUpRight;
    public float blendMouthLowerDownLeft;
    public float blendMouthLowerDownRight;
    public float blendMouthDimpleLeft;
    public float blendMouthDimpleRight;
    public float blendMouthLeft;
    public float blendMouthRight;
    public float blendMouthFunnel;
    public float blendMouthClose;
    public float blendMouthRollLower;
    public float blendMouthRollUpper;
    public float blendEyeLookUpLeft;
    public float blendEyeLookUpRight;
    public float blendEyeLookDownLeft;
    public float blendEyeLookDownRight;
    public float blendEyeLookOutLeft;
    public float blendEyeLookOutRight;
    public float blendEyeLookInLeft;
    public float blendEyeLookInRight;
    public float blendJawLeft;
    public float blendJawRight;

    /**
     * Body data from the pose tracker (shoulders, hips, hands).
     *
     * <p>The face model alone cannot tell a tilt of the body from a tilt of the head, and it loses
     * the user as soon as they turn away or step back. The pose model sees the whole person, which is
     * what lets the character turn with the body and keep moving when the face is out of view.</p>
     */
    public boolean body;
    /** Turn of the shoulders in degrees, positive when the right shoulder comes closer to the camera. */
    public float bodyYaw;
    /** Tilt of the shoulder line in degrees. */
    public float bodyRoll;
    /** Vertical movement of the body: -1 crouching, +1 standing tall. */
    public float bodyLift;
    /** Sideways lean of the body, -1 left, +1 right. */
    public float bodyShift;
    /** Highest hand above the shoulders, 0 (at the shoulder line) to 1 (raised high). */
    public float handUp;
    /** The pose source found a person (the avatar keeps working from it alone). */
    public boolean poseOnly;

    /**
     * Hands, as seen by the hand model.
     *
     * <p>These fields are what let the character follow a gesture: the number of raised fingers, the
     * place of the palm and of two fingertips, and how far the hand reaches towards the chin. All of
     * them are in the same coordinate system as {@link #centerX} and {@link #centerY}: -1 is the left
     * edge of the frame, +1 the right, and Y grows downwards.</p>
     */
    public boolean handsSeen;
    /** How many hands were seen: 0, 1 or 2. */
    public int hands;
    /** Fingers of the leading hand, 0 (fist) to 5 (open palm). */
    public int fingers;
    /** Fingers of the person's left and right hand: -1 when that hand is not seen. */
    public int fingersLeft = -1;
    public int fingersRight = -1;
    /** 0 for a fist, 1 for an open palm. */
    public float handOpen;
    /** Palm of the leading hand. */
    public float handX;
    public float handY;
    /** Tips of the index and middle finger of the leading hand. */
    public float indexX;
    public float indexY;
    public float middleX;
    public float middleY;
    /** Size of the hand in the frame, 0..2: how close it is to the camera. */
    public float handSpan;
    /** True when the leading hand is the person's left one. */
    public boolean handLeft;
    /**
     * Height of the face in the -1..1 space.
     *
     * <p>Needed for the gestures that are measured relative to the face: "the hand reaches the chin"
     * has to work both for someone sitting close to the phone and for someone across the room.</p>
     */
    public float faceHeight;
    /** How much the leading hand reaches the chin: 0 far, 1 touching. */
    public float chinTouch;

    /**
     * Каждая рука отдельно.
     *
     * <p>Одной руки мало: если человек поднял правую, левая должна остаться там, где её поставила
     * модель. Поэтому трекер кисти заполняет данные по сторонам, а хаб считает высоту и касание
     * подбородка для каждой руки своей. Сторона указана с точки зрения человека: left - его левая
     * рука, right - его правая.</p>
     */
    public boolean handSeenLeft;
    public boolean handSeenRight;
    /** Подъём ладони над подбородком, 0..1, по сторонам. */
    public float handUpLeft;
    public float handUpRight;
    /** Касание подбородка, 0..1, по сторонам. */
    public float chinTouchLeft;
    public float chinTouchRight;
    /** Открытая ладонь, 0..1, по сторонам. */
    public float handOpenLeft;
    public float handOpenRight;
    /** Ладонь в кадре, -1..1, по сторонам. */
    public float handXLeft;
    public float handYLeft;
    public float handXRight;
    public float handYRight;
    /** Точки кисти для расчёта касания подбородка: указательный, средний, ладонь. */
    public float indexXLeft;
    public float indexYLeft;
    public float middleXLeft;
    public float middleYLeft;
    public float palmXLeft;
    public float palmYLeft;
    public float indexXRight;
    public float indexYRight;
    public float middleXRight;
    public float middleYRight;
    public float palmXRight;
    public float palmYRight;

    /** Timestamp of the analysed frame, milliseconds. */
    public long timeMs;

    public void set(FaceSignals other) {
        found = other.found;
        body = other.body;
        bodyYaw = other.bodyYaw;
        bodyRoll = other.bodyRoll;
        bodyLift = other.bodyLift;
        bodyShift = other.bodyShift;
        handUp = other.handUp;
        poseOnly = other.poseOnly;
        handsSeen = other.handsSeen;
        hands = other.hands;
        fingers = other.fingers;
        fingersLeft = other.fingersLeft;
        fingersRight = other.fingersRight;
        handOpen = other.handOpen;
        handX = other.handX;
        handY = other.handY;
        indexX = other.indexX;
        indexY = other.indexY;
        middleX = other.middleX;
        middleY = other.middleY;
        handSpan = other.handSpan;
        handLeft = other.handLeft;
        faceHeight = other.faceHeight;
        chinTouch = other.chinTouch;
        handSeenLeft = other.handSeenLeft;
        handSeenRight = other.handSeenRight;
        handUpLeft = other.handUpLeft;
        handUpRight = other.handUpRight;
        chinTouchLeft = other.chinTouchLeft;
        chinTouchRight = other.chinTouchRight;
        handOpenLeft = other.handOpenLeft;
        handOpenRight = other.handOpenRight;
        handXLeft = other.handXLeft;
        handYLeft = other.handYLeft;
        handXRight = other.handXRight;
        handYRight = other.handYRight;
        indexXLeft = other.indexXLeft;
        indexYLeft = other.indexYLeft;
        middleXLeft = other.middleXLeft;
        middleYLeft = other.middleYLeft;
        palmXLeft = other.palmXLeft;
        palmYLeft = other.palmYLeft;
        indexXRight = other.indexXRight;
        indexYRight = other.indexYRight;
        middleXRight = other.middleXRight;
        middleYRight = other.middleYRight;
        palmXRight = other.palmXRight;
        palmYRight = other.palmYRight;
        yaw = other.yaw;
        pitch = other.pitch;
        roll = other.roll;
        eyeLeft = other.eyeLeft;
        eyeRight = other.eyeRight;
        smile = other.smile;
        mouthOpen = other.mouthOpen;
        centerX = other.centerX;
        centerY = other.centerY;
        scale = other.scale;
        blendshapes = other.blendshapes;
        faceUpright = other.faceUpright;
        faceUprightKnown = other.faceUprightKnown;
        geometric = other.geometric;
        smileGeo = other.smileGeo;
        mouthOpenGeo = other.mouthOpenGeo;
        browGeo = other.browGeo;
        eyeOpenGeo = other.eyeOpenGeo;
        blendEyeBlinkLeft = other.blendEyeBlinkLeft;
        blendEyeBlinkRight = other.blendEyeBlinkRight;
        blendJawOpen = other.blendJawOpen;
        blendMouthSmileLeft = other.blendMouthSmileLeft;
        blendMouthSmileRight = other.blendMouthSmileRight;
        blendMouthPucker = other.blendMouthPucker;
        blendBrowInnerUp = other.blendBrowInnerUp;
        blendBrowDownLeft = other.blendBrowDownLeft;
        blendBrowDownRight = other.blendBrowDownRight;
        blendCheekPuff = other.blendCheekPuff;
        blendEyeSquintLeft = other.blendEyeSquintLeft;
        blendEyeSquintRight = other.blendEyeSquintRight;
        blendMouthFrownLeft = other.blendMouthFrownLeft;
        blendMouthFrownRight = other.blendMouthFrownRight;
        blendTongueOut = other.blendTongueOut;
        blendBrowOuterUpLeft = other.blendBrowOuterUpLeft;
        blendBrowOuterUpRight = other.blendBrowOuterUpRight;
        blendCheekSquintLeft = other.blendCheekSquintLeft;
        blendCheekSquintRight = other.blendCheekSquintRight;
        blendNoseSneerLeft = other.blendNoseSneerLeft;
        blendNoseSneerRight = other.blendNoseSneerRight;
        blendEyeWideLeft = other.blendEyeWideLeft;
        blendEyeWideRight = other.blendEyeWideRight;
        blendMouthPressLeft = other.blendMouthPressLeft;
        blendMouthPressRight = other.blendMouthPressRight;
        blendMouthShrugUpper = other.blendMouthShrugUpper;
        blendMouthShrugLower = other.blendMouthShrugLower;
        blendMouthStretchLeft = other.blendMouthStretchLeft;
        blendMouthStretchRight = other.blendMouthStretchRight;
        blendMouthUpperUpLeft = other.blendMouthUpperUpLeft;
        blendMouthUpperUpRight = other.blendMouthUpperUpRight;
        blendMouthLowerDownLeft = other.blendMouthLowerDownLeft;
        blendMouthLowerDownRight = other.blendMouthLowerDownRight;
        blendMouthDimpleLeft = other.blendMouthDimpleLeft;
        blendMouthDimpleRight = other.blendMouthDimpleRight;
        blendMouthLeft = other.blendMouthLeft;
        blendMouthRight = other.blendMouthRight;
        blendMouthFunnel = other.blendMouthFunnel;
        blendMouthClose = other.blendMouthClose;
        blendMouthRollLower = other.blendMouthRollLower;
        blendMouthRollUpper = other.blendMouthRollUpper;
        blendEyeLookUpLeft = other.blendEyeLookUpLeft;
        blendEyeLookUpRight = other.blendEyeLookUpRight;
        blendEyeLookDownLeft = other.blendEyeLookDownLeft;
        blendEyeLookDownRight = other.blendEyeLookDownRight;
        blendEyeLookOutLeft = other.blendEyeLookOutLeft;
        blendEyeLookOutRight = other.blendEyeLookOutRight;
        blendEyeLookInLeft = other.blendEyeLookInLeft;
        blendEyeLookInRight = other.blendEyeLookInRight;
        blendJawLeft = other.blendJawLeft;
        blendJawRight = other.blendJawRight;
        timeMs = other.timeMs;
    }

    @Override
    public String toString() {
        return "FaceSignals{found=" + found + " yaw=" + yaw + " pitch=" + pitch + " roll=" + roll
                + " eyes=(" + eyeLeft + "," + eyeRight + ") smile=" + smile + " mouth=" + mouthOpen
                + " blendshapes=" + blendshapes + "}";
    }
}
