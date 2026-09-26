package com.echidna.studio;

import com.echidna.studio.anim.Ease;
import com.echidna.studio.anim.IdleDirector;
import com.echidna.studio.anim.Motions;
import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;
import com.echidna.studio.anim.Show;
import com.echidna.studio.anim.ShowLibrary;
import com.echidna.studio.anim.ShowPlayer;
import com.echidna.studio.track.FaceSignals;
import com.echidna.studio.track.TrackingMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Decides what the model does on every frame.
 *
 * <p>Modes:</p>
 * <ul>
 *   <li>{@link Mode#IDLE} - the model lives on its own: slow sway, wandering gaze, random motions.</li>
 *   <li>{@link Mode#SHOW} - one of the five authored shows owns the pose.</li>
 *   <li>{@link Mode#CAMERA} - the face tracker owns the pose; the model becomes the user.</li>
 *   <li>{@link Mode#MANUAL} - a single motion file from the gallery is playing.</li>
 * </ul>
 *
 * <p>Switching between modes cross fades the pose, so the character never snaps. Everything runs on
 * the GL thread, commands arrive from the UI thread through {@link #post(Runnable)}.</p>
 */
public final class ModelStage implements Motions {
    public enum Mode {
        IDLE, SHOW, CAMERA, MANUAL
    }

    public interface Listener {
        /** Called on the GL thread when the state worth showing in the UI changes. */
        void onStageState(String modeTitle, String detail, String currentMotion);
    }

    /**
     * Сколько длится переход между режимами.
     *
     * <p>Было 0,45 с: при включении камеры модель почти полсекунды выезжала из прошлой позы, и это
     * читалось как задержка. Теперь переход короткий и резкий, как переключение сцены.</p>
     */
    private static final float TRANSITION = 0.18f;
    /** Сколько эмоция должна держаться, прежде чем модель покажет своё выражение для неё. */
    private static final float MOOD_HOLD = 0.6f;
    /** Ниже этой силы эмоция считается случайной гримасой, а не настроением. */
    private static final float MOOD_STRENGTH = 0.45f;
    /** Как часто можно переключать выражение настроения. */
    private static final float MOOD_COOLDOWN = 5.0f;
    private static final int MOOD_NONE = -1;
    private static final float FACE_LOST_TIMEOUT = 0.5f;

    private final ShowPlayer showPlayer = new ShowPlayer();
    private final IdleDirector idle;
    private final TrackingMapper tracker;

    private final Pose outgoing = new Pose();     // pose we are leaving
    private final Pose incoming = new Pose();     // pose of the current mode, refreshed every frame
    private final Pose result = new Pose();       // pose handed to the model
    private float transition = 1.0f;
    /** Сколько эмоция держится и какое выражение уже показано: защита от мигания. */
    private float moodHold;
    private float moodCooldown;
    private int moodShown = MOOD_NONE;

    private AvatarBridge model;
    private Mode mode = Mode.IDLE;
    private Listener listener;

    /**
     * The newest tracking frame, published by the analysis thread and consumed by the GL thread.
     * Snapshots are immutable, so a single volatile reference is all the synchronisation needed.
     */
    private volatile FaceSignals pendingSignals;
    private final FaceSignals lostSignals = new FaceSignals();
    private float signalTimeout = 10.0f;

    // Written from the UI thread and the audio thread, read by the GL thread every frame.

    // The automatic blink of the framework runs in every mode; in the camera mode the eyelids are
    // driven by the tracker with full weight, which overrides it, unless the tracker has no blink
    // data at all - then ModelStage leaves them to the framework again.
    private boolean autoBlink = true;
    /** Выражения объёмной модели и то, кто их умеет включать. */
    private List<String> expressions;
    private ExpressionPlayer expressionPlayer;
    private String manualMotion;
    private float manualTime;
    /** Сколько ещё держится случайное выражение лица. */
    private float expressionTime;
    private float time;
    private String lastStateKey = "";

    public ModelStage() {
        final Random random = new Random(20260926L);
        idle = new IdleDirector(ShowLibrary.idleMotions(), random.nextLong());
        tracker = new TrackingMapper(true);
        lostSignals.found = false;
        incoming.eyeWeight = 0.0f;
    }

    public void attach(AvatarBridge model, Listener listener) {
        this.model = model;
        this.listener = listener;
        // Every character has its own motions, so the idle behaviour is rebuilt for the one that was
        // just loaded instead of playing names that only Echidna owns.
        final List<String> motions = model == null ? null : model.motionNames();
        idle.setPool(com.echidna.studio.anim.MotionPicker.idlePool(motions));
        idle.reset();
        showPlayer.stop();
        mode = Mode.IDLE;
        transition = 1.0f;
        incoming.reset();
        incoming.eyeWeight = 0.0f;
    }

    public Mode mode() {
        return mode;
    }

    public Show currentShow() {
        return showPlayer.current();
    }

    public String currentMotionName() {
        if (manualMotion != null && mode == Mode.MANUAL) {
            return manualMotion;
        }
        return model == null ? null : model.currentMotionName();
    }

    public TrackingMapper mapper() {
        return tracker;
    }

    /** Название распознанной эмоции: интерфейс показывает его в шапке. */
    public String emotionName() {
        return tracker.emotionName();
    }

    /** Сила распознанной эмоции, 0..1. */
    public float emotionIntensity() {
        return tracker.emotionIntensity();
    }

    /** True когда лицо явно что-то выражает: радость, злость, грусть, удивление. */
    public boolean emotional() {
        return tracker.expressive();
    }

    /** Номер распознанной эмоции. */
    public int emotion() {
        return tracker.emotion();
    }

    /**
     * Подбирает выражение лица под настроение.
     *
     * <p>У объёмных ригов вроде Нахиды вместо движений есть готовые выражения, и они гораздо
     * выразительнее процедурных бровей: звёзды в глазах, полуприкрытые глаза, румянец. Поэтому
     * когда человек держит одну эмоцию дольше полусекунды, модель включает своё выражение для неё,
     * а потом возвращается к живой мимике. Если у модели такого выражения нет, ничего не
     * происходит: лицо и так двигается за человеком.</p>
     */
    private void updateMoodExpression(float dt) {
        if (moodCooldown > 0.0f) {
            moodCooldown -= dt;
        }
        if (!tracker.expressive() || mode != Mode.CAMERA) {
            moodHold = 0.0f;
            if (moodShown != MOOD_NONE && moodCooldown <= 0.0f) {
                moodShown = MOOD_NONE;
            }
            return;
        }
        moodHold += dt;
        if (moodHold < MOOD_HOLD || moodCooldown > 0.0f) {
            return;
        }
        final int emotion = tracker.emotion();
        if (emotion == moodShown) {
            return;
        }
        if (tracker.emotionIntensity() < MOOD_STRENGTH) {
            return;
        }
        final String name = moodExpression(emotion);
        if (name != null && playExpression(name)) {
            moodShown = emotion;
            moodCooldown = MOOD_COOLDOWN;
            EchidnaLog.i("STAGE", "настроение " + tracker.emotionName() + ": выражение " + name);
        }
    }

    /**
     * Выражение модели под эмоцию: берётся первое, которое у неё действительно есть.
     *
     * <p>У объёмных ригов это выражения лица, у остальных - движения с говорящими именами
     * (у Ехидны «face_egao», «act_odoroku», «face_kanashimu»). Обе таблицы совпадений лежат в
     * {@link com.echidna.studio.anim.EmotionCatalog} и проверяются тестом по настоящим именам
     * файлов из сборки.</p>
     */
    public String moodExpression(int emotion) {
        return com.echidna.studio.anim.EmotionCatalog.expressionFor(emotion, knownExpressions());
    }


    /** Снимает нейтраль пользователя заново: по кнопке в интерфейсе. */
    public void calibrate() {
        tracker.calibrate();
        EchidnaLog.i("STAGE", "калибровка: нейтраль снимается заново");
    }

    public boolean isAutoBlink() {
        return autoBlink;
    }

    public void setAutoBlink(boolean value) {
        autoBlink = value;
    }




    // ------------------------------------------------------------------ commands

    public void startShow(Show show) {
        if (show == null) {
            return;
        }
        showPlayer.play(show);
        manualMotion = null;
        switchTo(Mode.SHOW);
        EchidnaLog.i("STAGE", "шоу \"" + show.id + "\" (" + show.duration + " с, "
                + show.segments.size() + " сегментов)");
    }

    public void startShow(String id) {
        startShow(ShowLibrary.byId(id));
    }

    public void playManualMotion(String name) {
        if (model == null || name == null) {
            return;
        }
        // Кнопка галереи может звать мошен по имени, которого у этой модели нет (у Валентины
        // набор короче, у Нахиды движений нет вообще) - тогда берём ближайшее или мимику.
        final String resolved = model.hasMotion(name)
                ? name
                : com.echidna.studio.anim.MotionPicker.resolve(model.motionNames(), name);
        if (resolved == null) {
            final String expression = com.echidna.studio.anim.ExpressionPicker
                    .resolve(knownExpressions(), name);
            if (expression == null) {
                EchidnaLog.w("STAGE", "нет мошена " + name);
                return;
            }
            playExpression(expression);
            EchidnaLog.i("STAGE", "мошен " + name + " сыгран выражением " + expression);
            return;
        }
        if (!model.hasMotion(resolved)) {
            EchidnaLog.w("STAGE", "нет мошена " + name);
            return;
        }
        manualMotion = resolved;
        manualTime = 0.0f;
        model.playMotion(resolved, 0.3f, FORCE);
        switchTo(Mode.MANUAL);
        EchidnaLog.i("STAGE", "ручной мошен " + resolved);
    }

    public void randomMotion() {
        final List<String> names = model == null ? null : model.motionNames();
        if (names == null || names.isEmpty()) {
            return;
        }
        final Random random = new Random(System.nanoTime());
        playManualMotion(names.get(random.nextInt(names.size())));
    }

    public void toCamera() {
        tracker.reset();
        switchTo(Mode.CAMERA);
        EchidnaLog.i("STAGE", "режим камеры");
    }

    public void toIdle() {
        switchTo(Mode.IDLE);
        EchidnaLog.i("STAGE", "режим ожидания");
    }

    private void switchTo(Mode next) {
        if (mode != next) {
            outgoing.set(result);
            transition = 0.0f;
            EchidnaLog.i("STAGE", "переход " + mode + " -> " + next);
        }
        mode = next;
    }

    public void setSignals(FaceSignals frame) {
        pendingSignals = frame;
    }

    // -------------------------------------------------------------------- update

    /** Produces the pose of this frame; called on the GL thread. */
    public Pose tick(float dt) {
        if (dt > 0.25f) {
            dt = 0.25f;
        }
        time += dt;

        final FaceSignals frame = pendingSignals;
        if (frame != null) {
            pendingSignals = null;
            tracker.onBlendshapes(frame);
            tracker.onSignals(frame, dt);
            signalTimeout = 0.0f;
        } else {
            signalTimeout += dt;
            if (signalTimeout > FACE_LOST_TIMEOUT) {
                lostSignals.timeMs += (long) (dt * 1000.0f);
                tracker.onSignals(lostSignals, dt);
            }
        }

        final boolean motionRunning = model != null && !model.isMotionFinished();

        switch (mode) {
            case SHOW: {
                if (!showPlayer.update(dt, incoming, this)) {
                    toIdle();
                    idle.update(dt, incoming, this, !motionRunning);
                }
                break;
            }
            case MANUAL: {
                manualTime += dt;
                idle.update(dt, incoming, null, false);
                incoming.weight = 0.0f;
                incoming.eyeWeight = 0.0f;
                if (!motionRunning && manualTime > 0.3f) {
                    manualMotion = null;
                    toIdle();
                }
                break;
            }
            case CAMERA: {
                // Всё движение идёт от камеры: рот модели повторяет рот человека, потому что
                // трекер лица читает его по губам. Микрофона в приложении нет.
                tracker.pose(dt, incoming);
                // Устойчивое настроение включает авторское выражение модели, если оно у неё есть.
                updateMoodExpression(dt);
                break;
            }
            case IDLE:
            default: {
                idle.update(dt, incoming, this, !motionRunning);
                break;
            }
        }

        // A random facial expression now and then keeps a VTuber rig alive even though it has no
        // motion files at all.
        if (expressionTime > 0.0f) {
            expressionTime -= dt;
            if (expressionTime <= 0.0f && model != null) {
                model.stopExpression();
            }
        } else if (model != null && mode != Mode.MANUAL && !model.expressionNames().isEmpty()
                && Math.random() < dt / 14.0) {
            final List<String> expressions = model.expressionNames();
            playExpression(expressions.get(new Random().nextInt(expressions.size())));
        }

        // A breath of life on top of every mode.
        incoming.bodyY = ParamLimits.bodyY(incoming.bodyY + 0.6f * (float) Math.sin(time * 0.21f));
        incoming.bodyZ = ParamLimits.bodyZ(incoming.bodyZ + 0.5f * (float) Math.sin(time * 0.27f + 1.0f));

        if (transition < 1.0f) {
            transition = Math.min(1.0f, transition + dt / TRANSITION);
            Pose.lerp(outgoing, incoming, Ease.SMOOTH.apply(transition), result);
        } else {
            result.set(incoming);
        }

        // The very last gate before the pose reaches the native model: whatever happened above, the
        // renderer only ever receives values the model can use.
        result.sanitize();

        notifyListener();
        return result;
    }

    /**
     * Shift of the model inside the frame as of the last {@link #tick}, in projection units.
     * Only the camera mode moves the model; shows and the idle director keep it centred.
     */
    public float viewOffsetX() {
        return result.offsetX;
    }

    public float viewOffsetY() {
        return result.offsetY;
    }

    /** Size multiplier of the model as of the last {@link #tick}. */
    public float viewZoom() {
        return result.zoom;
    }

    private void notifyListener() {
        if (listener == null) {
            return;
        }
        final String modeTitle;
        switch (mode) {
            case SHOW:
                modeTitle = showPlayer.current() != null ? showPlayer.current().title : "Шоу";
                break;
            case CAMERA:
                modeTitle = "Камера";
                break;
            case MANUAL:
                modeTitle = "Мошен";
                break;
            default:
                modeTitle = "Ожидание";
        }
        final String motion = currentMotionName();
        final String key = modeTitle + "|" + motion;
        if (!key.equals(lastStateKey)) {
            lastStateKey = key;
            listener.onStageState(modeTitle, detailFor(mode), motion);
        }
    }

    private String detailFor(Mode m) {
        switch (m) {
            case CAMERA:
                return tracker.status();
            case SHOW:
                return showPlayer.current() != null ? showPlayer.current().blurb : "";
            case MANUAL:
                return manualMotion == null ? "" : manualMotion;
            default:
                return "модель живёт сама";
        }
    }

    // ------------------------------------------------------------------- Motions

    @Override
    public void play(String name, float fadeIn, int priority) {
        if (model == null) {
            return;
        }
        // The show asks for the motion it was authored with; the character may know it under a
        // different name (another game, another set of files) or not have it at all.
        final String resolved = com.echidna.studio.anim.MotionPicker.resolve(model.motionNames(), name);
        if (resolved != null) {
            model.playMotion(resolved, fadeIn, priority);
            return;
        }
        // У объёмной модели и у рига вроде Нахиды движений нет: единственное, чем они умеют
        // «играть», - мимика. Название движения переводится в выражение, и шоу продолжает жить.
        if (model.motionNames().isEmpty()) {
            final String expression = com.echidna.studio.anim.ExpressionPicker
                    .resolve(knownExpressions(), name);
            if (expression != null) {
                playExpression(expression);
            }
        }
    }

    /** Имена выражений лица текущей модели (у VTuber-ригов они есть вместо движений). */
    public List<String> knownExpressions() {
        if (expressions != null) {
            return expressions;
        }
        return model == null ? new ArrayList<String>() : model.expressionNames();
    }

    /**
     * Выражения объёмного персонажа.
     *
     * <p>3D модель не знает {@code AvatarBridge}: её кости и морфы живут в движке VRM. Сцена всё
     * равно должна уметь показать список её выражений и включить любое из них по кнопке.</p>
     */
    public void setExpressions(List<String> names, ExpressionPlayer player) {
        this.expressions = names;
        this.expressionPlayer = player;
    }

    /** Включает выражение объёмной модели. */
    public interface ExpressionPlayer {
        void play(String name);
    }

    /** Проигрывает выражение лица, если у модели такое есть. */
    public boolean playExpression(String name) {
        if (model == null) {
            if (expressionPlayer != null && expressions != null && expressions.contains(name)) {
                expressionPlayer.play(name);
                expressionTime = 2.5f;
                return true;
            }
            return false;
        }
        final boolean played = model.playExpression(name);
        if (played) {
            expressionTime = 2.5f;
        }
        return played;
    }

    public List<String> knownMotions() {
        return model == null ? new ArrayList<String>() : model.motionNames();
    }

    /** Groups the motions of the model by prefix, used by the gallery UI. */
    public static List<String[]> motionGroups(List<String> names) {
        final String[][] prefixes = {
                {"act", "Действия"},
                {"face", "Лицо"},
                {"add", "Дополнительно"}
        };
        final List<String[]> result = new ArrayList<String[]>();
        for (int i = 0; i < prefixes.length; i++) {
            final List<String> bucket = new ArrayList<String>();
            for (int j = 0; j < names.size(); j++) {
                if (names.get(j).startsWith(prefixes[i][0] + "_")) {
                    bucket.add(names.get(j));
                }
            }
            if (!bucket.isEmpty()) {
                final String[] entry = new String[bucket.size() + 1];
                entry[0] = prefixes[i][1] + " · " + bucket.size();
                for (int k = 0; k < bucket.size(); k++) {
                    entry[k + 1] = bucket.get(k);
                }
                result.add(entry);
            }
        }
        return result;
    }

    /** The show library, exposed so that the UI does not need to know the ids. */
    public static List<Show> shows() {
        return ShowLibrary.shows();
    }

    /** True when an avatar is attached and the stage may run. */
    public boolean ready() {
        return model != null;
    }

}
