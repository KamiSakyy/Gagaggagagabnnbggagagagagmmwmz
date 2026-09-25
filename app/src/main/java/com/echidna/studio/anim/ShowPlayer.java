package com.echidna.studio.anim;

/**
 * Plays a {@link Show}: walks the timeline, asks for motion files at the right moment and writes
 * the interpolated pose for the current frame.
 */
public final class ShowPlayer {
    private Show show;
    private float time;
    private int cursor;
    private boolean active;
    private int pass;
    private final Pose frame = new Pose();

    public boolean isActive() {
        return active;
    }

    public Show current() {
        return show;
    }

    public float time() {
        return time;
    }

    /** Number of completed loops, useful for logs. */
    public int pass() {
        return pass;
    }

    public void play(Show s) {
        show = s;
        time = 0.0f;
        cursor = 0;
        pass = 0;
        active = s != null && !s.segments.isEmpty();
    }

    public void stop() {
        active = false;
        show = null;
        time = 0.0f;
        cursor = 0;
    }

    /**
     * Advances the timeline.
     *
     * @param dt     seconds since the previous frame
     * @param out    receives the pose of this frame
     * @param sink   receives motion start requests (may be null)
     * @return {@code true} while the show owns the pose
     */
    public boolean update(float dt, Pose out, Motions sink) {
        if (!active || show == null) {
            return false;
        }

        // A very long frame (app was in the background) must not fast forward the whole show.
        if (dt > 0.25f) {
            dt = 0.25f;
        }
        time += dt;

        if (time > show.duration) {
            if (show.loop) {
                time -= show.duration;
                cursor = 0;
                pass++;
            } else {
                time = show.duration;
            }
        }

        // Fire every segment that has just started.
        for (int i = cursor; i < show.segments.size(); i++) {
            Segment s = show.segments.get(i);
            if (s.start <= time) {
                if (sink != null && s.motion != null) {
                    sink.play(s.motion, s.motionFade, s.priority);
                }
                cursor = i + 1;
            } else {
                break;
            }
        }
        if (!show.loop && time >= show.duration) {
            active = false;
        }

        // Interpolate the owning segment.
        Segment owner = show.segmentAt(time);
        if (owner == null) {
            out.reset();
            out.weight = 0.0f;
            return active;
        }
        final float t = owner.ease.apply(owner.phase(time));
        Pose.lerp(owner.from, owner.to, t, frame);
        out.set(frame);
        return active;
    }

    /** Pose of the last frame, for callers that just want to read it. */
    public Pose pose() {
        return frame;
    }
}
