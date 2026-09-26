package com.echidna.studio.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Keeps the model alive when no show is running: a slow sway, wandering eyes, the occasional
 * raised brow and a random motion from the idle pool every few seconds.
 */
public final class IdleDirector {
    private static final float[] MOTION_DELAY = {5.0f, 12.0f};
    private static final float[] GAZE_DELAY = {2.6f, 6.0f};
    private static final float[] BROW_DELAY = {6.0f, 15.0f};

    private final Random random;
    private final List<String> pool = new ArrayList<String>();
    private final Damp gazeX = new Damp(0.5f);
    private final Damp gazeY = new Damp(0.45f);

    private float clock;
    private float sinceMotion;
    private float nextMotionIn;
    private float gazeTargetX;
    private float gazeTargetY;
    private float nextGazeIn;
    private float browPulse;      // seconds left of the current brow raise
    private float nextBrowIn;
    private String lastMotion;

    public IdleDirector(List<String> pool) {
        this(pool, 20260926L);
    }

    public IdleDirector(List<String> pool, long seed) {
        this.random = new Random(seed);
        setPool(pool);
        reset();
    }

    /**
     * Replaces the pool: the idle behaviour is rebuilt when another character is loaded, because
     * every character owns a different set of motions.
     */
    public void setPool(List<String> motions) {
        pool.clear();
        if (motions != null) {
            pool.addAll(motions);
        }
    }

    public void reset() {
        clock = 0.0f;
        sinceMotion = 0.0f;
        nextMotionIn = 2.5f + random.nextFloat() * 3.0f;
        gazeX.reset(0.0f);
        gazeY.reset(0.0f);
        gazeTargetX = 0.0f;
        gazeTargetY = 0.0f;
        nextGazeIn = 1.5f + random.nextFloat() * 2.0f;
        browPulse = 0.0f;
        nextBrowIn = 3.0f + random.nextFloat() * 5.0f;
        lastMotion = null;
    }

    public String lastMotion() {
        return lastMotion;
    }

    /**
     * @param motionFinished whether the motion currently playing has ended; a new idle motion is
     *                       only started when nothing else is running
     */
    public Pose update(float dt, Pose out, Motions sink, boolean motionFinished) {
        clock += dt;
        sinceMotion += dt;

        if (!motionFinished) {
            sinceMotion = 0.0f;
        } else if (sinceMotion >= nextMotionIn) {
            playIdleMotion(sink);
            sinceMotion = 0.0f;
            nextMotionIn = MOTION_DELAY[0] + random.nextFloat() * (MOTION_DELAY[1] - MOTION_DELAY[0]);
        }

        nextGazeIn -= dt;
        if (nextGazeIn <= 0.0f) {
            gazeTargetX = (random.nextFloat() * 2.0f - 1.0f) * 0.35f;
            gazeTargetY = (random.nextFloat() * 2.0f - 1.0f) * 0.22f;
            nextGazeIn = GAZE_DELAY[0] + random.nextFloat() * (GAZE_DELAY[1] - GAZE_DELAY[0]);
        }

        nextBrowIn -= dt;
        if (nextBrowIn <= 0.0f) {
            browPulse = 0.9f;
            nextBrowIn = BROW_DELAY[0] + random.nextFloat() * (BROW_DELAY[1] - BROW_DELAY[0]);
        }
        if (browPulse > 0.0f) {
            browPulse = Math.max(0.0f, browPulse - dt);
        }

        final float t = clock;
        out.angleX = ParamLimits.angleX(1.4f * (float) Math.sin(t * 0.27f + 0.4f));
        out.angleY = ParamLimits.angleY(2.0f * (float) Math.sin(t * 0.21f + 2.0f));
        out.angleZ = ParamLimits.angleZ(2.2f * (float) Math.sin(t * 0.35f) + 1.0f * (float) Math.sin(t * 0.73f + 1.2f));
        out.bodyX = ParamLimits.bodyX(1.1f * (float) Math.sin(t * 0.19f));
        out.bodyY = ParamLimits.bodyY(0.8f * (float) Math.sin(t * 0.16f + 0.5f));
        out.bodyZ = ParamLimits.bodyZ(0.9f * (float) Math.sin(t * 0.23f + 1.9f));

        out.eyeBallX = ParamLimits.eyeBallX(gazeX.update(gazeTargetX, dt) + 0.06f * (float) Math.sin(t * 1.9f));
        out.eyeBallY = ParamLimits.eyeBallY(gazeY.update(gazeTargetY, dt) + 0.04f * (float) Math.sin(t * 1.5f));

        final float brow = browPulse > 0.0f ? 0.3f * (float) Math.sin(Math.PI * (1.0f - browPulse / 0.9f)) : 0.0f;
        out.browLY = brow;
        out.browRY = brow;
        out.cheek = ParamLimits.unit(0.12f + 0.1f * brow);
        out.eyeLSmile = ParamLimits.unit(0.14f + 0.3f * brow);
        out.eyeRSmile = out.eyeLSmile;
        // The camera mode moves the model with the head of the user; leaving that mode must bring
        // the model back to the centre, otherwise a shifted frame would survive into the idle.
        out.offsetX = 0.0f;
        out.offsetY = 0.0f;
        out.zoom = 1.0f;
        out.mouthOpenY = ParamLimits.mouthOpen(0.02f + 0.02f * (float) Math.sin(t * 0.8f));
        out.mouthForm = ParamLimits.mouthForm(0.3f);
        out.weight = 0.6f;
        out.eyeWeight = 0.0f;
        return out;
    }

    private void playIdleMotion(Motions sink) {
        if (sink == null || pool.isEmpty()) {
            return;
        }
        String name = pool.get(random.nextInt(pool.size()));
        if (pool.size() > 1 && name.equals(lastMotion)) {
            name = pool.get((pool.indexOf(name) + 1 + random.nextInt(pool.size() - 1)) % pool.size());
        }
        lastMotion = name;
        sink.play(name, 0.9f, Motions.IDLE);
    }
}
