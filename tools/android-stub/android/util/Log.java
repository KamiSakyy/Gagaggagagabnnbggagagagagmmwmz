package android.util;

/**
 * Stand-in for {@code android.util.Log} used only by the offline unit test run.
 *
 * <p>The logic layer logs its milestones through {@link com.echidna.studio.EchidnaLog}; on a plain
 * JVM there is no android.util.Log at all (and the one inside android.jar only throws), so the tests
 * provide this tiny implementation that writes to stdout.</p>
 */
public final class Log {
    public static int v(String tag, String message) { return print("V", tag, message); }
    public static int d(String tag, String message) { return print("D", tag, message); }
    public static int i(String tag, String message) { return print("I", tag, message); }
    public static int w(String tag, String message) { return print("W", tag, message); }
    public static int e(String tag, String message) { return print("E", tag, message); }
    public static int e(String tag, String message, Throwable error) {
        print("E", tag, message + " (" + error + ")");
        return 0;
    }

    private static int print(String level, String tag, String message) {
        System.out.println(level + "/" + tag + ": " + message);
        return 0;
    }

    private Log() { }
}
