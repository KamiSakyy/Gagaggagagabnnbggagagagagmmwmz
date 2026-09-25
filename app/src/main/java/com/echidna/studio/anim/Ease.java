package com.echidna.studio.anim;

/**
 * Easing curves used between the keyframes of a show.
 *
 * <p>Every curve maps the normalised time of a segment (0..1) to the blend factor of the segment.
 * Overshooting curves (back/elastic) deliberately leave the 0..1 range, which is what makes a
 * transition feel lively instead of robotic.</p>
 */
public enum Ease {
    /** Constant speed. */
    LINEAR {
        @Override
        public float apply(float t) {
            return t;
        }
    },
    /** Slow at both ends, pleasant default for head movements. */
    SMOOTH {
        @Override
        public float apply(float t) {
            return t * t * (3.0f - 2.0f * t);
        }
    },
    /** Starts fast, settles gently. */
    OUT {
        @Override
        public float apply(float t) {
            float u = 1.0f - t;
            return 1.0f - u * u * u;
        }
    },
    /** Accelerates into the target, used for surprise reactions. */
    IN {
        @Override
        public float apply(float t) {
            return t * t * t;
        }
    },
    /** Small overshoot before settling - lively, "bouncy". */
    OUT_BACK {
        @Override
        public float apply(float t) {
            final float s = 1.70158f;
            float u = t - 1.0f;
            return 1.0f + (s + 1.0f) * u * u * u + s * u * u;
        }
    },
    /** Anticipation first, then a fast swing - reads as a deliberate dance move. */
    IN_OUT_BACK {
        @Override
        public float apply(float t) {
            final float s = 1.70158f * 1.525f;
            float u = t * 2.0f;
            if (u < 1.0f) {
                return 0.5f * (u * u * ((s + 1.0f) * u - s));
            }
            u -= 2.0f;
            return 0.5f * (u * u * ((s + 1.0f) * u + s) + 2.0f);
        }
    },
    /** A short pulse: 0 -> 1 -> 0, handy for winks and single accents. */
    PULSE {
        @Override
        public float apply(float t) {
            return (float) Math.sin(Math.PI * t);
        }
    },
    /** A fast attack followed by a slow release, used for blinks and gasps. */
    HIT {
        @Override
        public float apply(float t) {
            return (float) Math.pow(Math.sin(Math.PI * Math.pow(t, 0.72)), 1.6);
        }
    };

    /** Maps normalised segment time to the blend factor. */
    public abstract float apply(float t);
}
