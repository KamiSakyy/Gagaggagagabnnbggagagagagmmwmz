package com.echidna.studio.anim;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the motion of a character that fits what a show asked for.
 *
 * <p>The shows are authored once, but the app can show five different characters, and every one of
 * them has its own set of motion files: Echidna has 68, Emilia (Bunny) 125, the Nahida rig has no
 * motion files at all - only facial expressions. A show that asks for "act_egao" must therefore
 * land on the closest motion the current character really owns, and must keep working (on poses
 * alone) when there is nothing close at all.</p>
 */
public final class MotionPicker {

    /** Keywords of a mood, ordered from the most to the least typical. */
    private static final String[][] MOODS = {
            {"egao", "hohoemu", "happy", "smile", "warai", "warau", "niko", "egao"},
            {"kanashimu", "nayamu", "uru", "sad", "cry", "shizumu"},
            {"ikaru", "punpun", "okoru", "angry", "mukatsuku", "ikari"},
            {"bikkuri", "odoroku", "surprise", "tamagete"},
            {"tereru", "shy", "hazukashi", "cheek", "tere"},
            {"kangaeru", "shinken", "think", "kangae"},
            {"nedaru", "nemuru", "nebore", "sleep", "tameiki", "sigh"},
            {"talk", "kouyou", "shaberi", "hanasu"},
            {"unazuku", "nod", "unazu"},
            {"kyoton", "kubi", "tilt", "kashige", "tomadoi", "kangaeru"},
            {"konwaku", "tomadoi", "confus"},
            {"doya", "sumashi", "proud", "smug"},
            {"metozi", "gaze", "look", "shinken", "doya"},
            {"normal", "idle", "wait", "default"},
    };

    /** Motions that make a character look alive when nothing else is happening. */
    private static final String[] IDLE_ORDER = {
            "normal_w", "normal", "normal02", "hohoemu", "egao", "unazuku", "tereru",
            "sumashi", "tameiki", "metozi", "talk_small", "talk_normal", "nayamu", "kyoton",
    };

    private static final String[] CAMERA_ORDER = {
            "normal_w", "normal", "hohoemu", "egao", "talk_small", "unazuku",
    };

    private MotionPicker() {
    }

    /** Drops the folder, the suffix and the case of a motion name. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim();
        final int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        if (value.endsWith(".motion3.json")) {
            value = value.substring(0, value.length() - ".motion3.json".length());
        } else if (value.endsWith(".json")) {
            value = value.substring(0, value.length() - ".json".length());
        }
        return value;
    }

    /**
     * The motion of {@code available} that plays the role of {@code wanted}.
     *
     * @return the name of an existing motion, or null when the character has nothing close
     */
    public static String resolve(List<String> available, String wanted) {
        if (available == null || available.isEmpty()) {
            return null;
        }
        final String target = normalize(wanted);
        if (target.isEmpty()) {
            return null;
        }

        // 1. Exactly this motion.
        for (int i = 0; i < available.size(); i++) {
            if (normalize(available.get(i)).equals(target)) {
                return available.get(i);
            }
        }

        // 2. The same motion without the "_w" (the "wide shot" variant some models use).
        final String withoutVariant = target.endsWith("_w") ? target.substring(0, target.length() - 2) : target + "_w";
        for (int i = 0; i < available.size(); i++) {
            if (normalize(available.get(i)).equals(withoutVariant)) {
                return available.get(i);
            }
        }

        // 3. The same family: act_egao03 finds act_egao02, face_ikaru02 finds face_ikaru_w.
        final String stem = stripDigits(target);
        if (stem.length() >= 4) {
            String best = null;
            for (int i = 0; i < available.size(); i++) {
                final String candidate = normalize(available.get(i));
                if (candidate.startsWith(stem)) {
                    // A candidate in the same channel (act_/face_) wins over the other one.
                    if (best == null || sameChannel(candidate, target) && !sameChannel(best, target)) {
                        best = available.get(i);
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }

        // 4. The same mood, whatever the character calls it.
        final int[] moods = moodsOf(target);
        for (int m = 0; m < moods.length; m++) {
            final String[] keywords = MOODS[moods[m]];
            for (int k = 0; k < keywords.length; k++) {
                for (int i = 0; i < available.size(); i++) {
                    final String candidate = normalize(available.get(i));
                    if (candidate.contains(keywords[k]) && sameChannel(candidate, target)) {
                        return available.get(i);
                    }
                }
            }
            for (int k = 0; k < keywords.length; k++) {
                for (int i = 0; i < available.size(); i++) {
                    if (normalize(available.get(i)).contains(keywords[k])) {
                        return available.get(i);
                    }
                }
            }
        }
        return null;
    }

    /** The pool of motions the idle behaviour draws from, for the motions a model really has. */
    public static List<String> idlePool(List<String> available) {
        return poolByOrder(available, IDLE_ORDER, 12);
    }

    /** The pool of motions used while the camera watches a face. */
    public static List<String> cameraPool(List<String> available) {
        return poolByOrder(available, CAMERA_ORDER, 6);
    }

    /**
     * Builds a pool by walking a preference list.
     *
     * <p>When nothing from the list exists (a model may use names nobody expects) the whole motion
     * set becomes the pool: any motion is better than a frozen character.</p>
     */
    private static List<String> poolByOrder(List<String> available, String[] order, int limit) {
        final List<String> pool = new ArrayList<String>();
        if (available == null || available.isEmpty()) {
            return pool;
        }
        for (int i = 0; i < order.length && pool.size() < limit; i++) {
            final String keyword = order[i];
            String found = null;
            // prefer the exact name, then the "_w" variant, then anything containing the keyword
            for (int pass = 0; pass < 3 && found == null; pass++) {
                for (int j = 0; j < available.size(); j++) {
                    final String candidate = normalize(available.get(j));
                    final boolean matches;
                    if (pass == 0) {
                        matches = candidate.equals(keyword);
                    } else if (pass == 1) {
                        matches = candidate.equals(keyword + "_w");
                    } else {
                        matches = candidate.contains(keyword);
                    }
                    if (matches && !pool.contains(available.get(j))) {
                        found = available.get(j);
                        break;
                    }
                }
            }
            if (found != null) {
                pool.add(found);
            }
        }
        if (pool.isEmpty()) {
            for (int i = 0; i < available.size() && pool.size() < limit; i++) {
                pool.add(available.get(i));
            }
        }
        return pool;
    }

    private static boolean sameChannel(String candidate, String target) {
        final int candidateUnderscore = candidate.indexOf('_');
        final int targetUnderscore = target.indexOf('_');
        if (candidateUnderscore < 0 || targetUnderscore < 0) {
            return false;
        }
        return candidate.substring(0, candidateUnderscore).equals(target.substring(0, targetUnderscore));
    }

    private static String stripDigits(String name) {
        int end = name.length();
        while (end > 0 && (Character.isDigit(name.charAt(end - 1)) || name.charAt(end - 1) == '_')) {
            end--;
        }
        return end == 0 ? name : name.substring(0, end);
    }

    /** Indices of {@link #MOODS} whose keywords appear in the name, best first. */
    private static int[] moodsOf(String name) {
        final int[] found = new int[MOODS.length];
        int count = 0;
        for (int m = 0; m < MOODS.length; m++) {
            for (int k = 0; k < MOODS[m].length; k++) {
                if (name.contains(MOODS[m][k])) {
                    found[count++] = m;
                    break;
                }
            }
        }
        final int[] result = new int[count];
        System.arraycopy(found, 0, result, 0, count);
        return result;
    }
}
