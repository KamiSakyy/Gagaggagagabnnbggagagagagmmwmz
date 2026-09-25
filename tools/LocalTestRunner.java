import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the project's JUnit style tests on a plain JVM, without JUnit and without a network.
 *
 * <p>The CI job uses the real JUnit through Gradle; this runner exists so that the whole logic layer
 * can be verified offline during development. It understands the handful of features the tests use:
 * classes with a public no-arg constructor, methods annotated with {@code org.junit.Test}, and
 * assertions that throw {@link AssertionError}.</p>
 */
public final class LocalTestRunner {

    public static void main(String[] args) throws Exception {
        final List<Class<?>> testClasses = new ArrayList<Class<?>>();
        for (String arg : args) {
            collect(new File(arg), testClasses);
        }
        int run = 0;
        int failed = 0;
        final List<String> failures = new ArrayList<String>();
        for (Class<?> testClass : testClasses) {
            Method[] methods = testClass.getDeclaredMethods();
            java.util.Arrays.sort(methods, (a, b) -> a.getName().compareTo(b.getName()));
            for (Method method : methods) {
                if (method.getAnnotation(org.junit.Test.class) == null) {
                    continue;
                }
                run++;
                try {
                    Object instance = testClass.getDeclaredConstructor().newInstance();
                    method.setAccessible(true);
                    method.invoke(instance);
                } catch (Throwable t) {
                    Throwable cause = t.getCause() == null ? t : t.getCause();
                    failed++;
                    final String line = testClass.getSimpleName() + "." + method.getName()
                            + " -> " + cause;
                    failures.add(line);
                    System.out.println("FAIL " + line);
                    StackTraceElement[] trace = cause.getStackTrace();
                    for (int i = 0; i < Math.min(4, trace.length); i++) {
                        System.out.println("      at " + trace[i]);
                    }
                }
            }
        }
        System.out.println("---------------------------------------------");
        System.out.println("тестов запущено: " + run + ", провалено: " + failed);
        if (failed > 0) {
            for (String line : failures) {
                System.out.println("  ✗ " + line);
            }
            System.exit(1);
        }
        System.out.println("ВСЕ ТЕСТЫ ПРОШЛИ");
    }

    private static void collect(File file, List<Class<?>> out) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            java.util.Arrays.sort(children);
            for (File child : children) {
                collect(child, out);
            }
            return;
        }
        final String name = file.getName();
        if (!name.endsWith("Test.class") || name.contains("$")) {
            return;
        }
        final String path = file.getPath();
        int start = path.indexOf("com" + File.separator);
        if (start < 0) {
            return;
        }
        String className = path.substring(start, path.length() - ".class".length())
                .replace(File.separatorChar, '.');
        out.add(Class.forName(className));
    }

    private LocalTestRunner() { }
}
