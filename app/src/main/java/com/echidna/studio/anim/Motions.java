package com.echidna.studio.anim;

/** Receives motion playback requests produced by the animation layer. */
public interface Motions {
    /** Priorities understood by the model wrapper. */
    int IDLE = 1;
    int NORMAL = 2;
    int FORCE = 3;

    /**
     * Plays a motion file of the model.
     *
     * @param name    file name without the folder and the {@code .motion3.json} suffix,
     *                for example {@code act_egao}
     * @param fadeIn  cross fade time in seconds
     * @param priority one of {@link #IDLE}, {@link #NORMAL}, {@link #FORCE}
     */
    void play(String name, float fadeIn, int priority);
}
