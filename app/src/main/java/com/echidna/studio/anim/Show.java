package com.echidna.studio.anim;

import java.util.List;

/** A named, looping animation: a timeline of {@link Segment}s plus the text shown in the UI. */
public final class Show {
    public final String id;
    public final String title;
    public final String emoji;
    public final String blurb;
    public final float duration;
    public final boolean loop;
    public final List<Segment> segments;

    public Show(String id, String title, String emoji, String blurb,
                float duration, boolean loop, List<Segment> segments) {
        this.id = id;
        this.title = title;
        this.emoji = emoji;
        this.blurb = blurb;
        this.duration = duration;
        this.loop = loop;
        this.segments = segments;
    }

    /** The segment that owns {@code time} - the last one that already started. */
    public Segment segmentAt(float time) {
        Segment found = null;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (time >= s.start && (found == null || s.start >= found.start)) {
                found = s;
            }
        }
        return found;
    }

    @Override
    public String toString() {
        return "Show{" + id + ", " + duration + "s, " + segments.size() + " segments}";
    }
}
