package com.echidna.studio.track;

import com.echidna.studio.EchidnaLog;
import com.echidna.studio.anim.Damp;
import com.echidna.studio.anim.GestureReaction;
import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;

/**
 * Turns face tracking data into the pose of the model - the heart of the camera mode.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>map the head rotation of the user to the head and the body of the model,</li>
 *   <li>map the eye openness to the eyelids (a blink of the user becomes a blink of the model),</li>
 *   <li>map the smile and the mouth opening,</li>
 *   <li>smooth and limit everything so that tracking noise never looks like a glitch,</li>
 *   <li>fall back to an authored "demo" pose when no face is visible, so the model keeps living
 *       instead of freezing in the last tracked position.</li>
 * </ul>
 *
 * <p>The class is plain Java: the unit tests drive it with synthetic signals.</p>
 */
public final class TrackingMapper {

    /** How far the head may turn for the model to follow one to one. */
    /**
     * Усиление поворота головы.
     *
     * <p>Человек поворачивает голову на 30-40 градусов, а угол модели ограничен: без усиления
     * движение выглядело вялым. Трекер при этом занижает угол: спокойный поворот головы он читает
     * как десять-пятнадцать градусов. Усиление 1,8 подобранa так, чтобы такой поворот уже упирался
     * в предел модели в 30 градусов: движение головы видно так же, как его делает человек.</p>
     */
    private static final float YAW_GAIN = 1.80f;
    private static final float PITCH_GAIN = 1.70f;
    private static final float ROLL_GAIN = 1.75f;
    /** Предел угла головы по кадрам трекера: столько же, сколько принимает модель. */
    private static final float HEAD_LIMIT = 30.0f;

    /** Height of the face in the frame when the user sits normally; the zoom reference point. */
    private static final float NEUTRAL_FACE = 0.34f;
    private static final float SHIFT_GAIN = 0.16f;
    private static final float LIFT_GAIN = 0.10f;
    private static final float ZOOM_GAIN = 0.55f;

    /**
     * Сколько секунд человек может отвернуться, прежде чем модель уйдёт в демо-позу.
     *
     * <p>Было 0.45 с: одно моргание трекера - и на экране загоралось "демо-режим". Полторы секунды
     * переживают и потерю кадра, и то, что человек на секунду опустил глаза.</p>
     */
    private static final float FACE_LOST_GRACE = 1.5f;
    private static final float DEMO_IN_TIME = 1.2f;
    /** Выход из демонстрационной позы: короткий, чтобы первое движение не ждало полсекунды. */
    private static final float DEMO_OUT_TIME = 0.25f;

    /**
     * Время сглаживания по каналам.
     *
     * <p>Это и есть та самая задержка, из-за которой модель двигалась «через полсекунды после
     * меня»: чем больше число, тем плавнее и позже. Значения подобраны так, чтобы движение
     * повторялось почти сразу, но дрожание трекера не проходило на модель.</p>
     */
    private final Damp yaw = new Damp(0.045f, 0.20f, 5.0f);
    private final Damp pitch = new Damp(0.045f, 0.20f, 5.0f);
    private final Damp roll = new Damp(0.055f, 0.20f, 5.0f);
    private final Damp eyeL = new Damp(0.030f, 0.02f, 0.35f);
    private final Damp eyeR = new Damp(0.030f, 0.02f, 0.35f);
    private final Damp smile = new Damp(0.10f, 0.03f, 0.4f);
    private final Damp mouth = new Damp(0.045f, 0.03f, 0.4f);
    private final Damp centerX = new Damp(0.10f, 0.01f, 0.25f);
    private final Damp centerY = new Damp(0.10f, 0.01f, 0.25f);
    private final Damp faceSize = new Damp(0.22f);
    // Тело: плечи и наклон идут от трекера позы, поэтому сглаживаются отдельно.
    private final Damp bodyYaw = new Damp(0.10f, 0.4f, 8.0f);
    private final Damp bodyRoll = new Damp(0.12f, 0.4f, 8.0f);
    private final Damp bodyLift = new Damp(0.16f);
    private final Damp bodyShift = new Damp(0.14f);
    private final Damp handUp = new Damp(0.10f);
    /** Кисть: свежая точка отсчёта для жестов, поэтому сглаживается отдельно и мягко. */
    private final Damp chinTouch = new Damp(0.07f);
    private final Damp handOpenDamp = new Damp(0.08f);
    private final Damp handDx = new Damp(0.09f);
    private final Damp handDy = new Damp(0.09f);
    /** Видна ли каждая рука в последнем кадре: по этому руки модели и двигаются. */
    private boolean handSeenLeft;
    private boolean handSeenRight;

    /** Каждая рука отдельно: подъём, ладонь и касание подбородка. */
    private final Damp handUpLeft = new Damp(0.09f, 0.04f, 0.5f);
    private final Damp handUpRight = new Damp(0.09f, 0.04f, 0.5f);
    private final Damp handOpenLeft = new Damp(0.08f);
    private final Damp handOpenRight = new Damp(0.08f);
    private final Damp chinTouchLeft = new Damp(0.07f);
    private final Damp chinTouchRight = new Damp(0.07f);
    /** Реакция на счёт пальцев: кивки. */
    private final GestureReaction gestures = new GestureReaction();
    /**
     * Распознавание эмоций: по мышцам лица определяется, что человек чувствует.
     *
     * <p>Эмоция сразу уходит в лицо модели - брови, губы, щёки, слёзы, - и на неё же опирается
     * настроение движения: радость поднимает голову, грусть её опускает, злость подаёт её вперёд.</p>
     */
    private final EmotionDetector emotions = new EmotionDetector();
    /** Сглаживание каналов мимики: без него шум трекера дёргал бы брови по десять раз в секунду. */
    private final Damp browAngle = new Damp(0.05f, 0.04f, 0.5f);
    private final Damp browForm = new Damp(0.05f);
    private final Damp browHeight = new Damp(0.05f, 0.04f, 0.5f);
    private final Damp eyeWide = new Damp(0.04f);
    private final Damp glare = new Damp(0.06f);
    private final Damp tears = new Damp(0.35f);
    private final Damp pale = new Damp(0.30f);
    private final Damp angryFace = new Damp(0.20f);
    private final Damp mouthTension = new Damp(0.06f);
    private final Damp emotionWeight = new Damp(0.25f);
    /** Сколько человек уже показывает сильную эмоцию: по этому включается «игра» лица. */
    private float strongEmotionFor;
    /** Что удерживает восторг: он живёт дольше обычной радости. */
    private float delightFor;

    /** Знак каналов рук: задаётся кнопкой в настройках. */
    private boolean armInverted;

    private final Pose tracked = new Pose();
    private final Pose demo = new Pose();

    /**
     * Seconds since the last blink seen in the signals. It starts at zero, so a fresh session gives
     * the tracker six seconds to prove it can see blinks before the framework takes over.
     */
    private float sinceBlink;

    /**
     * Нейтраль пользователя: поза, в которой он сидит перед камерой.
     *
     * <p>Без неё человек, сидящий чуть боком или наклонив голову, навсегда получает повёрнутого
     * персонажа. Нейтраль снимается автоматически в первые полторы секунды уверенного трекинга и
     * может быть снята заново по кнопке.</p>
     */
    private float neutralYaw;
    private float neutralPitch;
    private float neutralRoll;
    private float neutralCenterX;
    private float neutralCenterY;
    private float neutralFace = NEUTRAL_FACE;
    private boolean neutralReady;
    private int neutralSamples;
    private float sumYaw;
    private float sumPitch;
    private float sumRoll;
    private float sumCenterX;
    private float sumCenterY;
    private float sumFace;
    private static final int NEUTRAL_SAMPLES = 24;
    /**
     * Автосъём нейтрали включён в приложении (человек садится как ему удобно, а персонаж смотрит
     * прямо). Тесты отключают его, когда проверяют само отображение углов.
     */
    private boolean autoCalibrate = true;
    /**
     * Направление поворота головы. У кого-то камера стоит зеркально, у кого-то нет, поэтому
     * направление поворота можно перевернуть кнопкой в интерфейсе, не трогая остальное.
     */
    private boolean turnInverted;
    private volatile boolean mirrored = true;
    private float lostFor = 10.0f;
    private float demoBlend = 1.0f;
    private float clock;
    private long lastSignalMs;

    public TrackingMapper() {
    }

    public TrackingMapper(boolean mirrored) {
        this.mirrored = mirrored;
    }

    /**
     * Mirrored tracking feels like a mirror: the user turns the head to their right and the model
     * turns to the same side of the screen. Exactly like VTube Studio's default.
     */
    public void setMirrored(boolean value) {
        mirrored = value;
    }

    public boolean isMirrored() {
        return mirrored;
    }

    /**
     * Направление каналов рук.
     *
     * <p>У разных ригов плечо поднимается от разных знаков, и по файлу модели это не видно: модели
     * хранят только диапазоны. Кнопка в приложении переключает знак, чтобы рука шла вверх, а не
     * вниз.</p>
     */
    public void setArmInverted(boolean value) {
        armInverted = value;
    }

    public boolean isArmInverted() {
        return armInverted;
    }

    public void reset() {
        yaw.reset();
        pitch.reset();
        roll.reset();
        eyeL.reset(1.0f);
        eyeR.reset(1.0f);
        smile.reset(0.0f);
        mouth.reset(0.0f);
        centerX.reset(0.0f);
        centerY.reset(0.0f);
        faceSize.reset(NEUTRAL_FACE);
        bodyYaw.reset(0.0f);
        bodyRoll.reset(0.0f);
        bodyLift.reset(0.0f);
        bodyShift.reset(0.0f);
        handUp.reset(0.0f);
        emotions.reset();
        browAngle.reset(0.0f);
        browForm.reset(0.0f);
        browHeight.reset(0.0f);
        eyeWide.reset(0.0f);
        glare.reset(0.0f);
        tears.reset(0.0f);
        pale.reset(0.0f);
        angryFace.reset(0.0f);
        mouthTension.reset(0.0f);
        emotionWeight.reset(0.0f);
        strongEmotionFor = 0.0f;
        delightFor = 0.0f;
        lostFor = 10.0f;
        demoBlend = 1.0f;
        clock = 0.0f;
        forgetNeutral();
    }

    /** Меняет направление поворота головы модели. */
    public void setTurnInverted(boolean value) {
        turnInverted = value;
    }

    public boolean isTurnInverted() {
        return turnInverted;
    }

    /** Автоматический съём нейтрали при первом уверенном трекинге. */
    public void setAutoCalibration(boolean value) {
        autoCalibrate = value;
        if (value) {
            forgetNeutral();
        }
    }

    /** Включает автоматический съём нейтрали: следующая уверенная секунда трекинга задаст её. */
    public void calibrate() {
        forgetNeutral();
    }

    private void forgetNeutral() {
        neutralReady = false;
        neutralSamples = 0;
        sumYaw = 0.0f;
        sumPitch = 0.0f;
        sumRoll = 0.0f;
        sumCenterX = 0.0f;
        sumCenterY = 0.0f;
        sumFace = 0.0f;
    }

    /** True when the neutral pose of the user has been measured. */
    public boolean isCalibrated() {
        return neutralReady;
    }

    /** Сколько кадров уже собрано для нейтрали: для подписи в интерфейсе. */
    public int neutralProgress() {
        return neutralSamples;
    }

    private void measureNeutral(FaceSignals s) {
        if (!autoCalibrate) {
            return;
        }
        if (neutralReady) {
            return;
        }
        sumYaw += s.yaw;
        sumPitch += s.pitch;
        sumRoll += s.roll;
        sumCenterX += s.centerX;
        sumCenterY += s.centerY;
        sumFace += s.scale > 0.05f ? s.scale : NEUTRAL_FACE;
        neutralSamples++;
        if (neutralSamples >= NEUTRAL_SAMPLES) {
            neutralYaw = sumYaw / neutralSamples;
            neutralPitch = sumPitch / neutralSamples;
            neutralRoll = sumRoll / neutralSamples;
            neutralCenterX = sumCenterX / neutralSamples;
            neutralCenterY = sumCenterY / neutralSamples;
            neutralFace = sumFace / neutralSamples;
            neutralReady = true;
            EchidnaLog.i("TRACK", String.format(java.util.Locale.ROOT,
                    "нейтраль снята: yaw %.1f, pitch %.1f, roll %.1f", neutralYaw, neutralPitch, neutralRoll));
        }
    }

    /** Feeds one analysed frame. */
    public void onSignals(FaceSignals s, float dt) {
        if (!s.found) {
            lostFor += dt;
            return;
        }
        if (Float.isNaN(dt) || Float.isInfinite(dt) || dt < 0.0f) {
            dt = 1.0f / 60.0f;
        }
        if (!usable(s)) {
            // A frame the tracker could not compute (degenerate matrix, zero sized box) is treated
            // as a lost face: better a short demo pose than a broken model.
            lostFor += dt;
            return;
        }
        lastSignalMs = s.timeMs;
        if (!s.poseOnly) {
            measureNeutral(s);
        }

        final float yawTarget = (mirrored ? -1.0f : 1.0f) * (s.yaw - neutralYaw);
        final float rollTarget = (mirrored ? -1.0f : 1.0f) * (s.roll - neutralRoll);
        yaw.update(yawTarget, dt);
        pitch.update(s.pitch - neutralPitch, dt);
        roll.update(rollTarget, dt);

        // Тело: плечи поворачиваются вместе с корпусом, наклон и руки добавляют живости.
        if (s.body) {
            bodyYaw.update((mirrored ? -1.0f : 1.0f) * (s.bodyYaw - (s.poseOnly ? 0.0f : 0.0f)), dt);
            bodyRoll.update((mirrored ? -1.0f : 1.0f) * s.bodyRoll, dt);
            bodyLift.update(s.bodyLift, dt);
            bodyShift.update((mirrored ? -1.0f : 1.0f) * s.bodyShift, dt);
        } else {
            bodyYaw.update(0.0f, dt);
            bodyRoll.update(0.0f, dt);
            bodyLift.update(0.0f, dt);
            bodyShift.update(0.0f, dt);
        }
        // Высота руки приходит из двух мест: от трекера позы (плечи) и от трекера кисти (ладонь
        // выше подбородка). Поэтому её нельзя обнулять только оттого, что тела в кадре не видно.
        if (s.body || s.handsSeen) {
            handUp.update(s.handUp, dt);
        } else {
            handUp.update(0.0f, dt);
        }
        // Рука: пальцы, открытая ладонь и путь к подбородку. Когда рук не видно, всё возвращается
        // в ноль, чтобы модель не осталась с поднятой рукой после того, как человек её опустил.
        if (s.handsSeen) {
            chinTouch.update(s.chinTouch, dt);
            handOpenDamp.update(s.handOpen, dt);
            handDx.update((mirrored ? -1.0f : 1.0f) * s.handX, dt);
            handDy.update(s.handY, dt);
        } else {
            chinTouch.update(0.0f, dt);
            handOpenDamp.update(0.0f, dt);
            handDx.update(0.0f, dt);
            handDy.update(0.0f, dt);
        }
        // Каждая рука живёт своей жизнью: если человек поднял только правую, левая остаётся в позе
        // модели. Значение -1 значит "руки не видно", и тогда сторона не трогается вовсе.
        // Флаги видимости берутся как есть: рука ушла из кадра - её сторона отпускается, и модель
        // возвращается к той позе, которую нарисовал художник.
        handSeenLeft = s.handSeenLeft;
        handSeenRight = s.handSeenRight;
        if (s.handSeenLeft) {
            handUpLeft.update(s.handUpLeft, dt);
            handOpenLeft.update(s.handOpenLeft, dt);
            chinTouchLeft.update(s.chinTouchLeft, dt);
        }
        if (s.handSeenRight) {
            handUpRight.update(s.handUpRight, dt);
            handOpenRight.update(s.handOpenRight, dt);
            chinTouchRight.update(s.chinTouchRight, dt);
        }
        gestures.update(s.handsSeen ? s.fingers : -1, dt);
        // Эмоции считаются до улыбки и рта: они уточняют их - настоящая улыбка приходит вместе со
        // щеками и прищуром, а грусть или злость забирают улыбку обратно.
        emotions.update(s, dt);
        updateFaceChannels(s, dt);
        // Улыбка и раскрытие рта берутся из самого сильного из трёх источников: поля трекера,
        // коэффициентов мимики и измерения по точкам лица. Так мимика не теряется там, где
        // нейросеть не уверена, а линейка по точкам всё ещё видит движение.
        final float smileFromBlend = (s.blendMouthSmileLeft + s.blendMouthSmileRight) * 0.5f;
        final float smileFromGeo = Math.max(0.0f, s.smileGeo);
        smile.update(Math.max(s.smile, Math.max(smileFromBlend, smileFromGeo)), dt);
        final float mouthFromGeo = s.geometric ? s.mouthOpenGeo : 0.0f;
        mouth.update(Math.max(s.mouthOpen, Math.max(s.blendJawOpen, mouthFromGeo)), dt);
        centerX.update(s.centerX - neutralCenterX, dt);
        centerY.update(s.centerY - neutralCenterY, dt);
        // Only a sensible face size feeds the zoom; a half closed frame would jump otherwise.
        if (s.scale > 0.05f) {
            faceSize.update(s.scale, dt);
        }

        // A blink is fast: closing snaps, opening follows the tracked value smoothly.
        final float rawLeft = s.eyeLeft;
        final float rawRight = s.eyeRight;
        updateEyes(eyeL, rawLeft, dt);
        updateEyes(eyeR, rawRight, dt);
        if (Math.min(rawLeft, rawRight) < 0.6f) {
            sinceBlink = 0.0f;
        } else {
            sinceBlink += dt;
        }

        lostFor = 0.0f;
    }

    /**
     * Разбирает мимику на каналы лица: брови, веки, щёки, губы, слёзы.
     *
     * <p>До этого места доходили только крупные каналы - улыбка, открытый рот, сомкнутые веки. Всё
     * остальное, из чего состоит живое лицо, терялось: нахмуренные брови, прищур, поджатые губы,
     * дрогнувший подбородок. Здесь каждый из них превращается в сглаженное значение, из которого
     * потом собирается и мимика, и эмоция.</p>
     */
    private void updateFaceChannels(FaceSignals s, float dt) {
        if (!s.blendshapes) {
            // Трекер без коэффициентов мимики: лицо всё равно повторяется по улыбке и рту.
            browAngle.update(0.0f, dt);
            browForm.update(0.0f, dt);
            browHeight.update(0.0f, dt);
            eyeWide.update(0.0f, dt);
            glare.update(0.0f, dt);
            tears.update(0.0f, dt);
            pale.update(0.0f, dt);
            angryFace.update(0.0f, dt);
            mouthTension.update(0.0f, dt);
            emotionWeight.update(0.0f, dt);
            return;
        }
        // Брови: высота складывается из поднятых внутренних и внешних концов минус сведённые.
        final float browUp = (s.blendBrowInnerUp + s.blendBrowOuterUpLeft + s.blendBrowOuterUpRight) / 3.0f;
        final float browDown = (s.blendBrowDownLeft + s.blendBrowDownRight) * 0.5f;
        // К коэффициентам добавляется измеренная высота бровей: она видна и на слабых движениях.
        final float browGeo = s.geometric ? s.browGeo * 0.7f : 0.0f;
        browHeight.update(Pose.clamp(browUp - browDown * 0.8f + browGeo, -1.0f, 1.0f), dt);
        // Наклон бровей: внутренние концы вверх - это грусть и мольба, вниз - злость и упрямство.
        browAngle.update(Pose.clamp(s.blendBrowInnerUp - browDown, -1.0f, 1.0f), dt);
        // Форма: сведённые брови образуют складку между ними.
        browForm.update(Pose.clamp(browDown - s.blendBrowInnerUp * 0.5f, -1.0f, 1.0f), dt);

        final float wide = (s.blendEyeWideLeft + s.blendEyeWideRight) * 0.5f;
        eyeWide.update(Pose.clamp(wide + (s.geometric ? Math.max(0.0f, s.browGeo) * 0.5f : 0.0f),
                0.0f, 1.0f), dt);
        final float squint = (s.blendEyeSquintLeft + s.blendEyeSquintRight) * 0.5f;
        final float sneer = (s.blendNoseSneerLeft + s.blendNoseSneerRight) * 0.5f;
        // «Злые глаза»: сведённые брови вместе с прищуром - это взгляд исподлобья.
        glare.update(Pose.clamp(browDown * 0.7f + squint * 0.5f + sneer * 0.3f, 0.0f, 1.0f), dt);
        // Слёзы: поджатые губы, сжатые брови внутренними концами и опущенный взгляд держатся
        // дольше остальных каналов, поэтому и сглаживаются медленнее - это состояние, а не гримаса.
        final float sorrow = (s.blendBrowInnerUp + (s.blendMouthFrownLeft + s.blendMouthFrownRight) * 0.5f
                + (s.blendMouthLowerDownLeft + s.blendMouthLowerDownRight) * 0.5f) / 3.0f;
        tears.update(Pose.clamp((sorrow - 0.45f) * 2.0f, 0.0f, 1.0f), dt);
        // Бледность: сильное удивление и испуг - лицо светлеет и замирает.
        pale.update(Pose.clamp((s.blendEyeWideLeft + s.blendEyeWideRight) * 0.25f
                + s.blendBrowInnerUp * 0.3f - squint * 0.4f - s.blendMouthSmileLeft * 0.3f, 0.0f, 1.0f), dt);
        // «Злое лицо» модели: нахмуренные брови, сморщенный нос, сжатые губы.
        angryFace.update(Pose.clamp(browDown * 0.8f + sneer * 0.5f
                + (s.blendMouthPressLeft + s.blendMouthPressRight) * 0.25f, 0.0f, 1.0f), dt);
        // Напряжение губ: поджатые губы и натянутые уголки - это сосредоточенность и сдержанность.
        mouthTension.update(Pose.clamp((s.blendMouthPressLeft + s.blendMouthPressRight) * 0.5f
                + (s.blendMouthStretchLeft + s.blendMouthStretchRight) * 0.5f
                - (s.blendMouthSmileLeft + s.blendMouthSmileRight) * 0.5f, -1.0f, 1.0f), dt);

        // Сила эмоции уходит в модель отдельным каналом: по ней лицо «играет» сильнее, чем по
        // спокойной мимике - брови выше, щёки круглее, глаза уже.
        emotionWeight.update(Pose.clamp(emotions.intensity(), 0.0f, 1.0f), dt);
        if (emotions.expressive()) {
            strongEmotionFor += dt;
        } else {
            strongEmotionFor = Math.max(0.0f, strongEmotionFor - dt * 0.5f);
        }
        // Восторг: широкая улыбка прищуренными глазами держится дольше, чем обычная радость.
        if (emotions.levelOf(EmotionDetector.DELIGHT) > 0.55f) {
            delightFor = 1.2f;
        } else {
            delightFor = Math.max(0.0f, delightFor - dt);
        }
    }

    /** Сколько пальцев показывает человек: по этому интерфейс рисует плашку. */
    public int shownFingers() {
        return gestures.shownCount();
    }

    /** Номер распознанной эмоции: видно в интерфейсе и в самопроверке. */
    public int emotion() {
        return emotions.emotion();
    }

    public String emotionName() {
        return emotions.name();
    }

    public float emotionIntensity() {
        return emotions.intensity();
    }

    /** True когда лицо явно что-то выражает: радость, злость, грусть и так далее. */
    public boolean expressive() {
        return emotions.expressive();
    }

    /** True when every value of the frame is a number the model can actually use. */
    private static boolean usable(FaceSignals s) {
        return isFinite(s.yaw) && isFinite(s.pitch) && isFinite(s.roll)
                && isFinite(s.eyeLeft) && isFinite(s.eyeRight)
                && isFinite(s.mouthOpen) && isFinite(s.smile)
                && isFinite(s.centerX) && isFinite(s.centerY);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static void updateEyes(Damp follower, float raw, float dt) {
        if (!follower.isPrimed()) {
            follower.reset(raw);
            return;
        }
        if (raw < follower.value() - 0.25f) {
            // Closing: jump halfway immediately, then let the follower finish the movement.
            follower.reset(follower.value() + (raw - follower.value()) * 0.6f);
        }
        follower.update(raw, dt);
    }

    /**
     * True when the tracker has not produced a blink for a long time.
     *
     * <p>Some trackers (and ML Kit on many devices) report the eyelids as fully open almost always.
     * A character that never blinks reads as a broken avatar, so in that case the eyelids are handed
     * over to the framework's own blink, which keeps the face alive.</p>
     */
    public boolean blinkStarved() {
        return sinceBlink > 6.0f;
    }

    /** True when the tracker is confident that a face is in front of the camera. */
    public boolean faceLive() {
        return lostFor < FACE_LOST_GRACE;
    }

    /** 0 while a face is tracked, 1 while the authored demo pose owns the model. */
    public float demoBlend() {
        return demoBlend;
    }

    /** Human readable state for the UI. */
    public String status() {
        if (faceLive() && expressive()) {
            return "эмоция: " + emotions.name() + " " + Math.round(emotions.intensity() * 100.0f) + "%";
        }
        if (faceLive() && gestures.shownCount() >= 0) {
            return "жест: " + gestures.shownCount() + " " + fingersWord(gestures.shownCount());
        }
        if (faceLive()) {
            return "лицо найдено";
        }
        if (demoBlend > 0.95f) {
            return "камера пока не видит лицо: сядь напротив и включи свет";
        }
        return "ищу лицо…";
    }

    /** Слово для числа пальцев: "4 пальца" читается понятнее, чем "4". */
    static String fingersWord(int count) {
        if (count % 10 == 1 && count % 100 != 11) {
            return "палец";
        }
        if (count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)) {
            return "пальца";
        }
        return "пальцев";
    }

    /**
     * Produces the pose of this frame.
     *
     * @param dt  seconds since the previous frame
     * @param out receives the pose
     */
    public Pose pose(float dt, Pose out) {
        clock += dt;
        if (lostFor < FACE_LOST_GRACE) {
            demoBlend = Math.max(0.0f, demoBlend - dt / DEMO_OUT_TIME);
        } else {
            demoBlend = Math.min(1.0f, demoBlend + dt / DEMO_IN_TIME);
        }

        buildTracked();
        buildDemo();

        Pose.lerp(tracked, demo, demoBlend, out);
        return out;
    }

    /**
     * Extra channels of the MediaPipe blendshapes that the raw signals do not carry: the puckered
     * lips, the pressed lips and the squint of the eyes change the mouth and the eyes of the model in
     * a way the plain smile/mouth pair cannot express.
     */
    private float extraMouthForm;
    private float extraEyeSquint;

    /** Feeds the blendshape channels that only the MediaPipe tracker fills in. */
    public void onBlendshapes(FaceSignals s) {
        if (!s.blendshapes) {
            extraMouthForm = 0.0f;
            extraEyeSquint = 0.0f;
            return;
        }
        final float pucker = (s.blendMouthPucker - s.blendMouthSmileLeft * 0.5f);
        final float frown = (s.blendMouthFrownLeft + s.blendMouthFrownRight) * 0.5f;
        final float dimple = 0.0f;
        extraMouthForm = Pose.clamp(-pucker * 1.2f - frown * 0.8f + dimple, -1.0f, 1.0f);
        extraEyeSquint = Pose.clamp((s.blendEyeSquintLeft + s.blendEyeSquintRight) * 0.5f, 0.0f, 1.0f);
    }

    private void buildTracked() {
        // Распознанная эмоция и её сила нужны сразу нескольким блокам ниже: лицу, улыбке и
        // настроению движения. Объявлены здесь, чтобы не считаться по второму разу.
        final float mood = emotionWeight.value();
        final int emotion = emotions.emotion();
        final float joy = emotions.joy();
        final float sadness = emotions.sadness();
        final float anger = emotions.anger();
        final float surprise = emotions.surprise();

        final float yawValue = Pose.clamp(yaw.value(), -HEAD_LIMIT, HEAD_LIMIT);
        final float pitchValue = Pose.clamp(pitch.value(), -HEAD_LIMIT, HEAD_LIMIT);
        final float rollValue = Pose.clamp(roll.value(), -HEAD_LIMIT, HEAD_LIMIT);

        // У ригов Live2D поворот головы влево-вправо - это ParamAngleX, а кивок - ParamAngleY
        // (сама модель это подтверждает: в её анимациях кивок act_unazuku идёт по AngleY, а
        // покачивание головой face_nayamu - по AngleX). Раньше эти оси были перепутаны, и человек
        // поворачивал голову, а модель кивала.
        tracked.angleX = ParamLimits.angleX(yawValue * YAW_GAIN * (turnInverted ? -1.0f : 1.0f));
        tracked.angleY = ParamLimits.angleY(-pitchValue * PITCH_GAIN);
        tracked.angleZ = ParamLimits.angleZ(rollValue * ROLL_GAIN);

        // The body follows the shoulders when the pose tracker sees them (a real turn of the body),
        // and the head otherwise. This is the part that makes the whole character turn with the user
        // instead of only the neck.
        final float bodyTurn = bodyYaw.value() * 0.85f;
        tracked.bodyX = ParamLimits.bodyX(bodyTurn + yawValue * 0.18f + bodyShift.value() * 4.0f);
        tracked.bodyZ = ParamLimits.bodyZ(bodyRoll.value() * 0.9f + rollValue * 0.16f);
        tracked.bodyY = ParamLimits.bodyY(-pitchValue * 0.12f + bodyLift.value() * 3.0f);

        // The whole model follows the user sideways as well, exactly like a mirror image of the
        // head position: this is the part that makes the avatar feel like it stands next to you
        // rather than being pinned to the middle of the frame.
        final float shiftX = (mirrored ? -1.0f : 1.0f) * centerX.value();
        tracked.offsetX = ParamLimits.offset(shiftX * SHIFT_GAIN);
        tracked.offsetY = ParamLimits.offset(centerY.value() * -LIFT_GAIN);
        tracked.zoom = ParamLimits.zoom(1.0f + (faceSize.value() - NEUTRAL_FACE) * ZOOM_GAIN);

        // A raised hand turns into a cheerful face: models without arm channels still show the
        // gesture, they just show it on the face and the head instead of on the arm.
        final float hands = ParamLimits.unit(handUp.value());
        // Руки модели поднимаются вместе с руками человека: у ригов с параметрами плеча, локтя и
        // кисти это настоящие движения рук, у остальных - наклон головы и взгляд на руку ниже.
        tracked.armY = hands;
        tracked.armInverted = armInverted;
        // Сторона модели: она смотрит на зрителя, поэтому при зеркальной картинке правая рука
        // человека двигает левую руку персонажа - как в зеркале.
        final boolean seenLeft = handSeenLeft;
        final boolean seenRight = handSeenRight;
        final boolean modelLeftFromUserRight = mirrored;
        final boolean leftSeen = modelLeftFromUserRight ? seenRight : seenLeft;
        final boolean rightSeen = modelLeftFromUserRight ? seenLeft : seenRight;
        tracked.armLeft = leftSeen
                ? (modelLeftFromUserRight ? handUpRight.value() : handUpLeft.value()) : -1.0f;
        tracked.armRight = rightSeen
                ? (modelLeftFromUserRight ? handUpLeft.value() : handUpRight.value()) : -1.0f;
        tracked.handOpenLeft = leftSeen
                ? (modelLeftFromUserRight ? handOpenRight.value() : handOpenLeft.value()) : -1.0f;
        tracked.handOpenRight = rightSeen
                ? (modelLeftFromUserRight ? handOpenLeft.value() : handOpenRight.value()) : -1.0f;
        tracked.chinTouchLeft = leftSeen
                ? (modelLeftFromUserRight ? chinTouchRight.value() : chinTouchLeft.value()) : 0.0f;
        tracked.chinTouchRight = rightSeen
                ? (modelLeftFromUserRight ? chinTouchLeft.value() : chinTouchRight.value()) : 0.0f;
        tracked.chinLeft = leftSeen;
        tracked.chinRight = rightSeen;
        tracked.handSeenLeft = leftSeen;
        tracked.handSeenRight = rightSeen;
        final float chin = ParamLimits.unit(chinTouch.value());
        tracked.chinTouch = chin;
        tracked.handOpen = ParamLimits.unit(handOpenDamp.value());
        tracked.handsSeen = hands > 0.02f || chin > 0.02f || handOpenDamp.value() > 0.02f;
        tracked.fingers = gestures.shownCount();

        // Кивки убраны: раньше модель кивала столько раз, сколько пальцев показал человек, и это
        // выглядело как «она делает то, чего я не делал». Счёт пальцев виден плашкой на экране, а
        // в лице модели остаётся только то, что человек действительно сделал.

        // Глаза и голова поворачиваются к руке, когда человек поднимает её: модель замечает жест.
        final float attention = ParamLimits.unit((hands - 0.15f) * 1.5f);
        if (attention > 0.001f) {
            final float lookX = Pose.clamp(handDx.value(), -1.0f, 1.0f);
            final float lookY = Pose.clamp(handDy.value(), -1.0f, 1.0f);
            tracked.angleX = ParamLimits.angleX(
                    ParamLimits.angleX(tracked.angleX) + lookX * 9.0f * attention);
            tracked.angleZ = ParamLimits.angleZ(
                    ParamLimits.angleZ(tracked.angleZ) - lookX * 3.0f * attention);
            tracked.eyeBallX = ParamLimits.eyeBallX(
                    ParamLimits.eyeBallX(tracked.eyeBallX) + lookX * 0.45f * attention);
            tracked.eyeBallY = ParamLimits.eyeBallY(lookY * 0.35f * attention);
        }

        // The gaze leads the head a little, which is what makes eye contact feel alive.
        final float baseEyeX = yawValue / 26.0f * 0.55f
                + centerX.value() * 0.35f + handDx.value() * 0.45f * attention;
        final float baseEyeY = pitchValue / 26.0f * 0.45f - centerY.value() * 0.25f
                - handDy.value() * 0.30f * attention + chin * 0.25f;
        tracked.eyeBallX = ParamLimits.eyeBallX(baseEyeX);
        tracked.eyeBallY = ParamLimits.eyeBallY(baseEyeY);

        // Настроение движения: каждая эмоция двигает голову и корпус по-своему - радость поднимает
        // и покачивает, удивление подаёт назад, злость вперёд, грусть опускает. Это то, что видно
        // даже на модели без каналов бровей и слёз.
        if (mood > 0.2f) {
            final float push = mood * 0.8f;
            tracked.angleY = ParamLimits.angleY(tracked.angleY
                    + (surprise + joy * 0.6f - sadness * 1.2f - anger * 0.5f) * push * 4.0f);
            tracked.angleZ = ParamLimits.angleZ(tracked.angleZ
                    + (joy * 0.8f - anger * 0.9f) * push * 3.0f);
            tracked.bodyY = ParamLimits.bodyY(tracked.bodyY
                    + (surprise * 1.2f - sadness * 0.8f) * push * 2.0f);
            tracked.bodyX = ParamLimits.bodyX(tracked.bodyX
                    + (anger * 0.9f - surprise * 0.6f) * push * 2.0f);
        }

        // Рука у подбородка: голова чуть опускается, глаза улыбаются - так это читается даже там,
        // где руки в модели нет.
        if (chin > 0.001f) {
            tracked.angleY = ParamLimits.angleY(
                    ParamLimits.angleY(tracked.angleY) + chin * 3.5f);
            tracked.angleZ = ParamLimits.angleZ(
                    ParamLimits.angleZ(tracked.angleZ) + 3.0f * chin * (mirrored ? -1.0f : 1.0f));
        }

        // Эмоция усиливает живую мимику: настоящая улыбка идёт вместе со щеками и прищуром, а
        // злость или грусть забирают её обратно - иначе улыбка трекера спорила бы с лицом.
        final float emotionSmile = ParamLimits.unit(joy * 0.9f + (delightFor > 0.0f ? 0.2f : 0.0f));
        final float smileValue = ParamLimits.unit(Math.max(
                Math.max(smile.value(), emotionSmile), hands * 0.55f));
        tracked.mouthOpenY = ParamLimits.mouthOpen(smoothStep(0.02f, 0.34f, mouth.value()));
        tracked.mouthForm = ParamLimits.mouthForm(-0.15f + smileValue * 1.0f + extraMouthForm * 0.6f);
        tracked.cheek = ParamLimits.unit((smileValue - 0.45f) * 2.0f + hands * 0.4f + chin * 0.3f);
        // Squinting and smiling both raise the lower eyelid of the model, which is what the
        // "smiling eyes" parameter does.
        final float eyeSmile = ParamLimits.unit(Math.max(smileValue * 0.8f, extraEyeSquint * 0.9f));
        tracked.eyeLSmile = eyeSmile;
        tracked.eyeRSmile = eyeSmile;

        // No brow tracking in the SDK: looking up raises them slightly, which reads as surprise.
        final float brow = Pose.clamp(-pitchValue * 0.012f + (smileValue - 0.5f) * 0.2f, -0.3f, 0.6f);
        tracked.browLY = brow;
        tracked.browRY = brow;

        // ------------------------------------------------------------------ эмоции лица
        //
        // Всё, что распознано по мышцам лица, уходит в модель отдельными каналами. Модели различаются
        // набором параметров (у Нахиды есть свои «злые» и «бледность», у Эмилии - «злые глаза» и
        // слёзы), поэтому лишние каналы движок просто не тронет: их отсекает EchidnaModel.
        tracked.emotion = emotion;
        tracked.emotionWeight = mood;
        tracked.browAngle = ParamLimits.unitSign(browAngle.value());
        tracked.browForm = ParamLimits.unitSign(browForm.value());
        tracked.browX = ParamLimits.unitSign(browForm.value() * 0.6f);
        tracked.eyeWideL = ParamLimits.unit(eyeWide.value() * 0.9f + surprise * 0.5f);
        tracked.eyeWideR = tracked.eyeWideL;
        tracked.glareL = ParamLimits.unit(glare.value() + anger * 0.6f);
        tracked.glareR = tracked.glareL;
        tracked.tears = ParamLimits.unit(tears.value() + (sadness > 0.6f ? (sadness - 0.6f) * 2.0f : 0.0f));
        tracked.pale = ParamLimits.unit(pale.value() + surprise * 0.4f);
        tracked.angryFace = ParamLimits.unit(angryFace.value() + anger * 0.5f);
        tracked.mouthTension = ParamLimits.unitSign(mouthTension.value());
        // Зрачок «в кучку» при сильной радости и восторге: так модель выглядит счастливой.
        tracked.eyeYorime = ParamLimits.unit(Math.max(joy - 0.55f, 0.0f) * 2.2f
                + (delightFor > 0.0f ? 0.35f : 0.0f));
        // Высота бровей: к мимике добавляется сама эмоция - удивление вскидывает брови, злость и
        // грусть их сдвигают.
        final float browBase = browHeight.value();
        tracked.browLY = Pose.clamp(browBase + surprise * 0.6f - anger * 0.5f - sadness * 0.25f, -1.0f, 1.0f);
        tracked.browRY = tracked.browLY;

        // The eyes of the model follow the lids of the user one to one.
        final float openLeft = mirrored ? eyeR.value() : eyeL.value();
        final float openRight = mirrored ? eyeL.value() : eyeR.value();
        tracked.eyeLOpen = ParamLimits.eyeOpen(smoothStep(0.18f, 0.62f, openLeft));
        tracked.eyeROpen = ParamLimits.eyeOpen(smoothStep(0.18f, 0.62f, openRight));
        // While the tracker really reports blinks the eyelids are driven one to one; if it does not,
        // the weight goes to zero and the framework's automatic blink takes over.
        tracked.eyeWeight = blinkStarved() ? 0.0f : 1.0f;
        tracked.weight = 1.0f;
    }

    /**
     * Поза, в которую уходит модель, когда лица не видно.
     *
     * <p>Раньше это было собственное покачивание персонажа - и человек справедливо говорил, что
     * модель «делает то, чего он не делал». Теперь это спокойное состояние: голова и корпус в
     * ноль, глаза открыты, вес позы нулевой. Всё, что остаётся, - собственное дыхание рига и
     * автоматическое моргание движка, как в VTube Studio с выключенным трекингом.</p>
     */
    private void buildDemo() {
        demo.angleX = 0.0f;
        demo.angleY = 0.0f;
        demo.angleZ = 0.0f;
        demo.bodyX = 0.0f;
        demo.bodyY = 0.0f;
        demo.bodyZ = 0.0f;
        demo.eyeBallX = 0.0f;
        demo.eyeBallY = 0.0f;
        demo.mouthOpenY = 0.0f;
        demo.mouthForm = 0.0f;
        demo.browLY = 0.0f;
        demo.browRY = 0.0f;
        demo.cheek = 0.0f;
        demo.eyeLSmile = 0.0f;
        demo.eyeRSmile = 0.0f;
        demo.eyeLOpen = 1.0f;
        demo.eyeROpen = 1.0f;
        demo.offsetX = 0.0f;
        demo.offsetY = 0.0f;
        demo.zoom = 1.0f;
        // Руки в спокойном состоянии не подняты.
        demo.armY = 0.0f;
        demo.armLeft = -1.0f;
        demo.armRight = -1.0f;
        demo.handOpenLeft = -1.0f;
        demo.handOpenRight = -1.0f;
        demo.handSeenLeft = false;
        demo.handSeenRight = false;
        demo.chinTouch = 0.0f;
        demo.chinTouchLeft = 0.0f;
        demo.chinTouchRight = 0.0f;
        demo.chinLeft = false;
        demo.chinRight = false;
        demo.handOpen = 0.0f;
        demo.handsSeen = false;
        demo.fingers = -1;
        // Автоматическое моргание движка остаётся: это единственное, что может делать персонаж,
        // которого никто не видит, - и оно есть в любой VTuber-студии.
        demo.eyeWeight = 0.0f;
        // Вес нулевой: спокойное состояние не навязывает модели позу, а отпускает её.
        demo.weight = 0.0f;
        demo.armInverted = armInverted;
        demo.emotion = 0;
        demo.emotionWeight = 0.0f;
        demo.browAngle = 0.0f;
        demo.browForm = 0.0f;
        demo.browX = 0.0f;
        demo.eyeWideL = 0.0f;
        demo.eyeWideR = 0.0f;
        demo.glareL = 0.0f;
        demo.glareR = 0.0f;
        demo.tears = 0.0f;
        demo.pale = 0.0f;
        demo.angryFace = 0.0f;
        demo.mouthTension = 0.0f;
        demo.eyeYorime = 0.0f;
    }

    static float smoothStep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) {
            return x >= edge1 ? 1.0f : 0.0f;
        }
        float t = Pose.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
