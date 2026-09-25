package com.echidna.studio;

import java.util.List;

/**
 * What the stage needs from the avatar.
 *
 * <p>Keeping this as an interface instead of a direct dependency on the Live2D model means the whole
 * behaviour layer (shows, idle, camera mapping, transitions) runs in a plain JVM unit test with a
 * fake avatar - the native renderer is the only part that cannot be unit tested, and it is the part
 * that is checked on the emulator instead.</p>
 */
public interface AvatarBridge {
    /**
     * Plays a motion file by name.
     *
     * @return true when the motion exists and was accepted
     */
    boolean playMotion(String name, float fadeIn, int priority);

    /** True when the motion currently playing has ended (or none is playing). */
    boolean isMotionFinished();

    /** Name of the motion playing right now, or null. */
    String currentMotionName();

    /** Every motion the avatar knows. */
    List<String> motionNames();

    /** True when a motion with this name exists. */
    boolean hasMotion(String name);
}
