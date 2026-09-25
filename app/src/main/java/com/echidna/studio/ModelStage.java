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

    private static final float TRANSITION = 0.45f;
    private static final float FACE_LOST_TIMEOUT = 0.5f;

    private final ShowPlayer showPlayer = new ShowPlayer();
    private final IdleDirector idle;
    private final TrackingMapper tracker;

    private final Pose outgoing = new Pose();     // pose we are leaving
    private final Pose incoming = new Pose();     // pose of the current mode, refreshed every frame
    private final Pose result = new Pose();       // pose handed to the model
    private float transition = 1.0f;

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
    private volatile float micLevel;
    /** Усиление микрофона по умолчанию; им же заменяется испорченное значение. */
    private static final float DEFAULT_MIC_GAIN = 1.0f;

    private volatile float micGain = DEFAULT_MIC_GAIN;
    private volatile boolean micEnabled;

    // The automatic blink of the framework runs in every mode; in the camera mode the eyelids are
    // driven by the tracker with full weight, which overrides it, unless the tracker has no blink
    // data at all - then ModelStage leaves them to the framework again.
    private boolean autoBlink = true;
    private String manualMotion;
    private float manualTime;
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

    public boolean isAutoBlink() {
        return autoBlink;
    }

    public void setAutoBlink(boolean value) {
        autoBlink = value;
    }

    public void setMicEnabled(boolean enabled) {
        micEnabled = enabled;
    }

    public boolean isMicEnabled() {
        return micEnabled;
    }

    public void setMicGain(float gain) {
        // Math.max would keep a NaN, and a NaN gain would make every mouth value unusable.
        micGain = Float.isNaN(gain) ? DEFAULT_MIC_GAIN : Math.max(0.1f, Math.min(8.0f, gain));
    }

    /** Raw RMS level of the microphone, 0..1. */
    public void setMicLevel(float level) {
        micLevel = ParamLimits.unit(level);
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
        if (!model.hasMotion(name)) {
            EchidnaLog.w("STAGE", "нет мошена " + name);
            return;
        }
        manualMotion = name;
        manualTime = 0.0f;
        model.playMotion(name, 0.3f, FORCE);
        switchTo(Mode.MANUAL);
        EchidnaLog.i("STAGE", "ручной мошен " + name);
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
                tracker.pose(dt, incoming);
                applyMicLipsync(dt);
                break;
            }
            case IDLE:
            default: {
                idle.update(dt, incoming, this, !motionRunning);
                break;
            }
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

    /**
     * The microphone adds to the tracked mouth instead of replacing it, and the tracked mouth keeps
     * a small floor so that the lips do not slam shut between words.
     */
    private void applyMicLipsync(float dt) {
        if (!micEnabled) {
            return;
        }
        final float shaped = (float) Math.pow(micLevel * micGain * 3.0f, 0.6);
        final float micMouth = ParamLimits.mouthOpen(shaped);
        final float tracked = Math.max(incoming.mouthOpenY * 0.35f, micMouth);
        final float k = 1.0f - (float) Math.exp(-dt / (micMouth > incoming.mouthOpenY ? 0.035f : 0.13f));
        incoming.mouthOpenY = ParamLimits.mouthOpen(
                incoming.mouthOpenY + (tracked - incoming.mouthOpenY) * k);
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
        if (model != null) {
            model.playMotion(name, fadeIn, priority);
        }
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
