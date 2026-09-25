package com.echidna.studio.anim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The five authored shows of the app plus the motion pools used by the idle behaviour.
 *
 * <p>Everything here is pure data: which motion file starts when, and how the head, the body, the
 * gaze, the mouth and the brows move in between. The values were tuned against the ranges the
 * model actually accepts (see {@code ParamLimits}) and the motion list shipped inside
 * {@code Echidna.model3.json}.</p>
 */
public final class ShowLibrary {

    public static final String ID_CUTE = "cute";
    public static final String ID_DANCE = "dance";
    public static final String ID_CHARM = "charm";
    public static final String ID_GREET = "greet";
    public static final String ID_SURPRISE = "surprise";

    private static final List<Show> SHOWS = build();

    private ShowLibrary() {
    }

    public static List<Show> shows() {
        return SHOWS;
    }

    public static Show byId(String id) {
        for (int i = 0; i < SHOWS.size(); i++) {
            if (SHOWS.get(i).id.equals(id)) {
                return SHOWS.get(i);
            }
        }
        return null;
    }

    /** Motions that look good when the model is simply "alive" on screen. */
    public static List<String> idleMotions() {
        return Collections.unmodifiableList(Arrays.asList(
                "act_normal_w",
                "face_normal_w",
                "act_hohoemu",
                "act_egao",
                "act_egao02",
                "act_unazuku",
                "act_tereru",
                "face_talk_small",
                "face_talk_normal",
                "act_sumashi",
                "act_tameiki",
                "face_metozi"
        ));
    }

    /** Motions used while the model watches a face in camera mode when nothing else happens. */
    public static List<String> cameraIdleMotions() {
        return Collections.unmodifiableList(Arrays.asList(
                "act_normal_w",
                "face_normal_w",
                "act_hohoemu",
                "face_talk_small",
                "act_egao"
        ));
    }

    private static List<Show> build() {
        List<Show> list = new ArrayList<Show>();
        list.add(cute());
        list.add(dance());
        list.add(charm());
        list.add(greet());
        list.add(surprise());
        return Collections.unmodifiableList(list);
    }

    // ---------------------------------------------------------------- cute

    private static Show cute() {
        Timeline t = new Timeline();
        t.add(0.9f, P().head(3, 5, 4).cheek(0.35f).brow(0.15f, 0.15f).mouth(0.05f, 0.5f).gaze(0.15f, 0.05f)
                .eyeSmile(0.35f, 0.35f).weight(0.8f).get(), Ease.SMOOTH, "act_tereru", 0.5f);
        t.add(0.9f, P().head(6, 7, 6).cheek(0.5f).brow(0.2f, 0.2f).mouth(0.12f, 0.7f).gaze(0.2f, 0.08f)
                .eyeSmile(0.6f, 0.6f).weight(0.85f).get(), Ease.SMOOTH, "act_egao", 0.6f);
        t.add(1.0f, P().head(4, 4, 2).cheek(0.45f).mouth(0.08f, 0.6f).gaze(0.1f, 0.05f)
                .eyeSmile(0.5f, 0.5f).weight(0.8f).get(), Ease.SMOOTH, "act_egao02", 0.6f);
        t.winkRight(0.13f, 0.22f, 0.0f);
        t.add(1.2f, P().head(-2, 8, -6).cheek(0.4f).brow(0.3f, 0.3f).mouth(0.1f, 0.6f).gaze(0.25f, 0.1f)
                .eyeSmile(0.45f, 0.45f).weight(0.8f).get(), Ease.SMOOTH, "act_hohoemu", 0.7f);
        t.add(1.0f, P().head(0, 2, -2).cheek(0.4f).mouth(0.06f, 0.55f).gaze(0.05f, 0.02f)
                .eyeSmile(0.55f, 0.55f).weight(0.75f).get(), Ease.SMOOTH, null, 0f);
        t.add(0.8f, P().head(7, 0, 3).brow(0.25f, 0.25f).cheek(0.4f).mouth(0.15f, 0.5f).gaze(0f, 0.05f)
                .eyeSmile(0.4f, 0.4f).weight(0.85f).get(), Ease.OUT, "act_unazuku", 0.5f);
        t.add(0.9f, P().head(-3, -6, -4).cheek(0.35f).brow(0.2f, 0.2f).mouth(0.05f, 0.6f).gaze(-0.2f, -0.05f)
                .eyeSmile(0.5f, 0.5f).weight(0.8f).get(), Ease.SMOOTH, "act_egao03", 0.7f);
        t.add(1.1f, P().head(2, 3, 5).cheek(0.45f).mouth(0.1f, 0.65f).gaze(0.1f, 0.1f)
                .eyeSmile(0.55f, 0.55f).weight(0.8f).get(), Ease.SMOOTH, "face_talk_small", 0.8f);
        t.add(1.0f, P().head(1, 0, 0).cheek(0.3f).mouth(0.06f, 0.5f).gaze(0f, 0f)
                .eyeSmile(0.4f, 0.4f).weight(0.75f).get(), Ease.SMOOTH, null, 0f);
        t.add(1.2f, P().head(-1, 4, 3).cheek(0.35f).brow(0.4f, 0.4f).mouth(0.08f, 0.6f).gaze(0.1f, 0.15f)
                .eyeSmile(0.45f, 0.45f).weight(0.8f).get(), Ease.SMOOTH, "act_hohoemu", 0.8f);
        t.add(1.45f, P().head(0, 0, 0).cheek(0.2f).mouth(0f, 0.35f).gaze(0f, 0f)
                .eyeSmile(0.2f, 0.2f).weight(0.6f).get(), Ease.SMOOTH, null, 0f);
        return t.build(ID_CUTE, "Милота", "\uD83C\uDF38",
                "Застенчивая, милая Ехидна: улыбки, подмигивание и кивки", Motions.NORMAL);
    }

    // --------------------------------------------------------------- dance

    private static Show dance() {
        // Rhythm driven choreography in 120 BPM: one bar lasts two seconds, the body swings once
        // per bar, the head twice per bar and the accents land on every beat.
        final float beat = 0.5f;                  // 120 bpm -> one beat every half second
        final float bar = beat * 4.0f;             // 2 s
        final float step = 0.25f;                  // eight keyframes per bar keeps the curve smooth
        final float total = bar * 6.0f;            // six bars, twelve seconds
        final float bodyOmega = (float) (Math.PI); // one full swing per bar
        final float headOmega = (float) (2.0 * Math.PI);   // two nods per bar
        final float accentOmega = (float) (4.0 * Math.PI); // one accent per beat
        final String[] barMotions = {
                "act_kouyou", "act_egao02", "act_bikkuri",
                "act_egao03", "act_kouyou", "act_egao02"
        };
        Timeline t = new Timeline();
        final int frames = Math.round(total / step);
        final int framesPerBar = Math.round(bar / step);
        for (int i = 0; i < frames; i++) {
            final float time = i * step;
            String motion = null;
            if (i % framesPerBar == 0) {
                motion = barMotions[(i / framesPerBar) % barMotions.length];
            }
            final Pose p = P()
                    .head(
                            3.0f + 4.0f * (float) Math.sin(time * headOmega),
                            7.0f * (float) Math.sin(time * bodyOmega + 0.7f),
                            8.0f * (float) Math.sin(time * headOmega + 1.6f))
                    .body(
                            5.5f * (float) Math.sin(time * bodyOmega),
                            2.5f * (float) Math.sin(time * headOmega + 0.4f),
                            5.0f * (float) Math.sin(time * bodyOmega + 2.4f))
                    .gaze(
                            0.5f * (float) Math.sin(time * bodyOmega + 1.0f),
                            0.2f * (float) Math.sin(time * headOmega))
                    .mouth(0.12f + 0.22f * (float) Math.abs(Math.sin(time * accentOmega * 0.5f)), 0.8f)
                    .brow(0.15f + 0.25f * (float) Math.abs(Math.sin(time * accentOmega * 0.5f)),
                            0.15f + 0.25f * (float) Math.abs(Math.sin(time * accentOmega * 0.5f)))
                    .cheek(0.35f)
                    .eyeSmile(0.55f, 0.55f)
                    .weight(0.85f)
                    .get();
            t.add(step, p, Ease.SMOOTH, motion, motion == null ? 0f : 0.3f);
        }
        return t.build(ID_DANCE, "Танец", "\uD83D\uDC83",
                "Ритмичный танец: корпус в такт 120 BPM и смена поз каждые два такта", Motions.NORMAL);
    }

    // --------------------------------------------------------------- charm

    private static Show charm() {
        Timeline t = new Timeline();
        t.add(1.8f, P().head(-3, 12, -9).cheek(0.55f).brow(0.15f, 0.3f).mouth(0.15f, 0.7f).gaze(-0.4f, 0.15f)
                .eyeSmile(0.5f, 0.55f).weight(0.75f).get(), Ease.SMOOTH, "face_cheek_on", 0.5f);
        t.add(0.8f, P().head(-6, 6, -6).cheek(0.4f).brow(0.2f, 0.3f).mouth(0.2f, 0.6f).gaze(-0.2f, 0.3f)
                .eyeSmile(0.5f, 0.5f).weight(0.8f).get(), Ease.SMOOTH, "act_nedaru", 0.6f);
        t.add(0.8f, P().head(-2, 14, -10).cheek(0.5f).brow(0.2f, 0.35f).mouth(0.14f, 0.75f).gaze(-0.5f, 0.25f)
                .eyeSmile(0.6f, 0.6f).weight(0.85f).get(), Ease.SMOOTH, null, 0f);
        // Lowered lids while looking at the viewer from under the lashes, with an authored blink.
        t.eyes(1.2f, 1.0f, 0.5f, Ease.SMOOTH, P().head(-4, 10, -12).cheek(0.55f).brow(0.2f, 0.4f)
                .mouth(0.1f, 0.8f).gaze(-0.4f, 0.3f).eyeSmile(0.6f, 0.6f).weight(0.85f).get());
        t.eyes(0.6f, 0.5f, 0.5f, Ease.SMOOTH, P().head(-6, 8, -14).cheek(0.6f).brow(0.25f, 0.45f)
                .mouth(0.06f, 0.85f).gaze(-0.35f, 0.35f).eyeSmile(0.6f, 0.6f).weight(0.9f).get());
        t.eyes(0.12f, 0.5f, 0.0f, Ease.HIT, P().head(-6, 8, -14).cheek(0.6f).brow(0.25f, 0.45f)
                .mouth(0.05f, 0.85f).gaze(-0.35f, 0.35f).eyeSmile(0.6f, 0.6f).weight(0.9f).get());
        t.eyes(0.2f, 0.0f, 0.5f, Ease.OUT, P().head(-5f, 9, -13).cheek(0.6f).brow(0.25f, 0.45f)
                .mouth(0.08f, 0.85f).gaze(-0.35f, 0.3f).eyeSmile(0.6f, 0.6f).weight(0.9f).get());
        t.eyes(1.1f, 0.5f, 1.0f, Ease.SMOOTH, P().head(-2, 10, -8).cheek(0.5f).brow(0.2f, 0.35f)
                .mouth(0.12f, 0.75f).gaze(-0.3f, 0.2f).eyeSmile(0.55f, 0.55f).weight(0.85f).get());
        t.add(1.2f, P().head(2, -6, 8).cheek(0.35f).brow(0.2f, 0.25f).mouth(0.1f, 0.5f).gaze(0.3f, -0.1f)
                .eyeSmile(0.4f, 0.4f).weight(0.8f).get(), Ease.SMOOTH, "face_metozi", 0.7f);
        t.add(1.6f, P().head(5, 3, 4).cheek(0.6f).brow(0.4f, 0.4f).mouth(0.05f, 0.8f).gaze(0.2f, 0.05f)
                .eyeSmile(0.5f, 0.5f).weight(0.85f).get(), Ease.SMOOTH, "act_tereru", 0.7f);
        t.add(1.6f, P().head(-2, 8, -5).cheek(0.45f).brow(0.25f, 0.3f).mouth(0.08f, 0.7f).gaze(-0.25f, 0.1f)
                .eyeSmile(0.5f, 0.5f).weight(0.75f).get(), Ease.SMOOTH, "act_hohoemu", 0.7f);
        t.add(1.6f, P().head(-5, 4, -8).cheek(0.4f).brow(0.2f, 0.3f).mouth(0.15f, 0.65f).gaze(-0.3f, 0.2f)
                .eyeSmile(0.5f, 0.5f).weight(0.8f).get(), Ease.SMOOTH, "act_nedaru", 0.7f);
        t.add(1.4f, P().head(0, 0, 0).cheek(0.25f).mouth(0f, 0.4f).gaze(0f, 0f)
                .eyeSmile(0.25f, 0.25f).weight(0.6f).get(), Ease.SMOOTH, null, 0f);
        return t.build(ID_CHARM, "Соблазнение", "\uD83D\uDC9C",
                "Медленный взгляд, прикрытые ресницы и лёгкая улыбка", Motions.NORMAL);
    }

    // --------------------------------------------------------------- greet

    private static Show greet() {
        Timeline t = new Timeline();
        t.add(0.6f, P().head(8, -4, 2).brow(0.35f, 0.35f).mouth(0.35f, 0.6f).gaze(0.1f, 0f)
                .eyeSmile(0.4f, 0.4f).weight(0.9f).get(), Ease.OUT, "act_unazuku", 0.3f);
        t.add(0.8f, P().head(2, 6, 3).cheek(0.45f).brow(0.25f, 0.25f).mouth(0.12f, 0.85f).gaze(0.15f, 0.05f)
                .eyeSmile(0.65f, 0.65f).weight(0.85f).get(), Ease.SMOOTH, "act_egao", 0.5f);
        t.add(1.2f, P().head(-3, 9, -5).cheek(0.5f).brow(0.3f, 0.3f).mouth(0.2f, 0.7f).gaze(0.3f, 0.1f)
                .eyeSmile(0.6f, 0.6f).weight(0.85f).get(), Ease.SMOOTH, "act_egao02", 0.7f);
        t.add(1.4f, P().head(4, 0, 6).cheek(0.4f).brow(0.2f, 0.2f).mouth(0.08f, 0.6f).gaze(0f, 0.08f)
                .eyeSmile(0.5f, 0.5f).weight(0.75f).get(), Ease.SMOOTH, "act_hohoemu", 0.8f);
        t.add(1.2f, P().head(6, -2, 1).brow(0.3f, 0.3f).mouth(0.25f, 0.65f).gaze(0.05f, 0f)
                .eyeSmile(0.45f, 0.45f).weight(0.85f).get(), Ease.OUT, "act_unazuku", 0.6f);
        t.add(1.4f, P().head(-2, 5, -4).cheek(0.35f).brow(0.2f, 0.2f).mouth(0.15f, 0.6f).gaze(0.15f, 0.05f)
                .eyeSmile(0.5f, 0.5f).weight(0.75f).get(), Ease.SMOOTH, "face_talk_normal", 0.8f);
        t.add(1.4f, P().head(1, 7, 4).cheek(0.5f).brow(0.25f, 0.25f).mouth(0.1f, 0.8f).gaze(0.2f, 0.05f)
                .eyeSmile(0.55f, 0.55f).weight(0.8f).get(), Ease.SMOOTH, "act_egao03", 0.8f);
        t.add(1.6f, P().head(0, 0, 0).cheek(0.2f).mouth(0f, 0.35f).gaze(0f, 0f)
                .eyeSmile(0.2f, 0.2f).weight(0.55f).get(), Ease.SMOOTH, "act_normal_w", 0.9f);
        return t.build(ID_GREET, "Приветствие", "\uD83D\uDC4B",
                "Кивок, улыбка и приветливый разговор", Motions.NORMAL);
    }

    // ------------------------------------------------------------ surprise

    private static Show surprise() {
        Timeline t = new Timeline();
        t.add(0.22f, P().head(-9, 0, -4).brow(0.9f, 0.9f).mouth(0.6f, 0.1f).gaze(0f, -0.1f)
                .weight(1.0f).get(), Ease.OUT, "act_bikkuri", 0.08f, Motions.FORCE);
        t.add(0.6f, P().head(-7, 2, -2).brow(0.85f, 0.85f).mouth(0.5f, 0.05f).gaze(0f, -0.15f)
                .weight(1.0f).get(), Ease.SMOOTH, null, 0f);
        t.add(0.8f, P().head(-4, -3, 3).brow(0.7f, 0.7f).mouth(0.45f, 0.1f).gaze(-0.1f, -0.2f)
                .weight(0.95f).get(), Ease.SMOOTH, "act_odoroku", 0.15f);
        t.add(0.8f, P().head(3, 5, -9).brow(0.35f, 0.5f).mouth(0.15f, -0.25f).gaze(0.15f, 0.1f)
                .weight(0.9f).get(), Ease.SMOOTH, "act_kyoton", 0.4f);
        t.add(1.0f, P().head(4, 6, -11).brow(0.3f, 0.55f).mouth(0.1f, -0.2f).gaze(0.2f, 0.05f)
                .weight(0.9f).get(), Ease.SMOOTH, null, 0f);
        t.add(1.2f, P().head(0, 3, 0).cheek(0.4f).brow(0.2f, 0.2f).mouth(0.12f, 0.7f).gaze(0.05f, 0f)
                .eyeSmile(0.5f, 0.5f).weight(0.85f).get(), Ease.SMOOTH, "act_egao", 0.8f);
        t.add(1.4f, P().head(-2, 6, -3).cheek(0.5f).brow(0.25f, 0.25f).mouth(0.08f, 0.75f).gaze(0.1f, 0.05f)
                .eyeSmile(0.55f, 0.55f).weight(0.8f).get(), Ease.SMOOTH, "act_hohoemu", 0.8f);
        t.add(1.6f, P().head(2, 5, 3).cheek(0.45f).brow(0.2f, 0.2f).mouth(0.1f, 0.7f).gaze(0.15f, 0.05f)
                .eyeSmile(0.55f, 0.55f).weight(0.8f).get(), Ease.SMOOTH, "act_egao02", 0.8f);
        t.add(2.4f, P().head(0, 0, 0).cheek(0.15f).mouth(0f, 0.3f).gaze(0f, 0f)
                .eyeSmile(0.15f, 0.15f).weight(0.5f).get(), Ease.SMOOTH, "act_normal_w", 1.0f);
        return t.build(ID_SURPRISE, "Сюрприз", "\u2728",
                "Испуг, удивление, недоумение и облегчение", Motions.FORCE);
    }

    // ----------------------------------------------------------- authoring

    /** Chains keyframes into segments; every keyframe starts where the previous one ended. */
    static final class Timeline {
        private final List<Segment> segments = new ArrayList<Segment>();
        private Pose last = neutral();
        private float time;

        float now() {
            return time;
        }

        Timeline add(float duration, Pose to, Ease ease, String motion, float fade) {
            return add(duration, to, ease, motion, fade, Motions.NORMAL);
        }

        Timeline add(float duration, Pose to, Ease ease, String motion, float fade, int priority) {
            Pose from = new Pose(last);
            segments.add(new Segment(time, time + duration, from, to, ease, motion, fade, priority));
            time += duration;
            last = to;
            return this;
        }

        /**
         * An authored blink that keeps every other channel untouched. The eye weight is switched on
         * for the blink and released afterwards so that the automatic blinking of the framework
         * takes over again.
         */
        Timeline blink(float closeDuration, float openDuration, float closedValue) {
            Pose closed = new Pose(last);
            closed.eyeLOpen = closedValue;
            closed.eyeROpen = closedValue;
            closed.eyeWeight = 1.0f;
            Pose open = new Pose(last);
            open.eyeLOpen = 1.0f;
            open.eyeROpen = 1.0f;
            open.eyeWeight = 0.0f;
            add(closeDuration, closed, Ease.SMOOTH, null, 0f);
            add(openDuration, open, Ease.SMOOTH, null, 0f);
            return this;
        }

        /** A one eyed wink - the cute reaction of the "Милота" show. */
        Timeline winkRight(float closeDuration, float openDuration, float closedValue) {
            Pose closed = new Pose(last);
            closed.eyeROpen = closedValue;
            closed.eyeLOpen = 1.0f;
            closed.eyeWeight = 1.0f;
            Pose open = new Pose(last);
            open.eyeLOpen = 1.0f;
            open.eyeROpen = 1.0f;
            open.eyeWeight = 0.0f;
            add(closeDuration, closed, Ease.HIT, null, 0f);
            add(openDuration, open, Ease.OUT, null, 0f);
            return this;
        }

        /**
         * Moves the eyelids to an explicit value while the rest of the pose comes from
         * {@code pose}; used for the lowered lashes of the "Соблазнение" show.
         */
        Timeline eyes(float duration, float fromOpen, float toOpen, Ease ease, Pose to) {
            Pose from = new Pose(last);
            from.eyeLOpen = fromOpen;
            from.eyeROpen = fromOpen;
            from.eyeWeight = 1.0f;
            to.eyeLOpen = toOpen;
            to.eyeROpen = toOpen;
            to.eyeWeight = 1.0f;
            segments.add(new Segment(time, time + duration, from, to, ease, null, 0f, Motions.NORMAL));
            time += duration;
            last = to;
            return this;
        }

        Show build(String id, String title, String emoji, String blurb, int priority) {
            return new Show(id, title, emoji, blurb, time, true,
                    Collections.unmodifiableList(new ArrayList<Segment>(segments)));
        }
    }

    /** Neutral pose with the eyes left to the automatic blinking. */
    static Pose neutral() {
        Pose p = new Pose();
        p.eyeWeight = 0f;
        return p;
    }

    private static Builder P() {
        return new Builder();
    }

    /** Tiny fluent helper that keeps the show definitions readable. */
    static final class Builder {
        private final Pose p = neutral();

        Builder head(float x, float y, float z) {
            p.angleX = x;
            p.angleY = y;
            p.angleZ = z;
            return this;
        }

        Builder body(float x, float y, float z) {
            p.bodyX = x;
            p.bodyY = y;
            p.bodyZ = z;
            return this;
        }

        Builder gaze(float x, float y) {
            p.eyeBallX = x;
            p.eyeBallY = y;
            return this;
        }

        Builder mouth(float open, float form) {
            p.mouthOpenY = open;
            p.mouthForm = form;
            return this;
        }

        Builder brow(float left, float right) {
            p.browLY = left;
            p.browRY = right;
            return this;
        }

        Builder cheek(float value) {
            p.cheek = value;
            return this;
        }

        Builder eyeSmile(float left, float right) {
            p.eyeLSmile = left;
            p.eyeRSmile = right;
            return this;
        }

        Builder weight(float value) {
            p.weight = value;
            return this;
        }

        Pose get() {
            return p;
        }
    }
}
