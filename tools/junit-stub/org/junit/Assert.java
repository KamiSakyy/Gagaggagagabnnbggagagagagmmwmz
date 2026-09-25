package org.junit;

/**
 * Minimal stand-in for the handful of JUnit assertions this project uses.
 * The real JUnit runs in CI; this exists so that the local check works without network access.
 */
public final class Assert {

    public static void assertTrue(String message, boolean condition) {
        if (!condition) {
            fail(message);
        }
    }

    public static void assertTrue(boolean condition) {
        assertTrue(null, condition);
    }

    public static void assertFalse(String message, boolean condition) {
        assertTrue(message, !condition);
    }

    public static void assertFalse(boolean condition) {
        assertTrue(null, !condition);
    }

    public static void assertEquals(String message, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail(message + " ожидалось <" + expected + ">, получилось <" + actual + ">");
        }
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(null, expected, actual);
    }

    public static void assertEquals(String message, long expected, long actual) {
        if (expected != actual) {
            fail(message + " ожидалось <" + expected + ">, получилось <" + actual + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        assertEquals(null, expected, actual);
    }

    public static void assertEquals(String message, double expected, double actual, double delta) {
        if (Double.compare(expected, actual) != 0 && Math.abs(expected - actual) > delta) {
            fail(message + " ожидалось <" + expected + "> (+-" + delta + "), получилось <" + actual + ">");
        }
    }

    public static void assertEquals(double expected, double actual, double delta) {
        assertEquals(null, expected, actual, delta);
    }

    public static void assertEquals(String message, float expected, float actual, float delta) {
        assertEquals(message, (double) expected, (double) actual, (double) delta);
    }

    public static void assertEquals(float expected, float actual, float delta) {
        assertEquals(null, expected, actual, delta);
    }

    public static void assertNotNull(String message, Object object) {
        assertTrue(message == null ? "ожидался не null" : message, object != null);
    }

    public static void assertNotNull(Object object) {
        assertNotNull(null, object);
    }

    public static void assertNull(String message, Object object) {
        assertTrue(message == null ? "ожидался null" : message, object == null);
    }

    public static void assertNull(Object object) {
        assertNull(null, object);
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    private Assert() { }
}
