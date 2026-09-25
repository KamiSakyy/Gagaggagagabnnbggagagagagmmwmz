package java.lang.invoke;

/**
 * Bootclasspath stub, not shipped in the APK.
 *
 * <p>The offline check compiles the app against {@code android.jar}, which (unlike a real device)
 * does not contain {@code LambdaMetafactory}. Without it javac cannot emit the {@code invokedynamic}
 * call behind every lambda and method reference. This file provides the two signatures javac looks up,
 * so lambdas compile offline exactly like they do in the Gradle build.</p>
 */
public final class LambdaMetafactory {
    public static CallSite metafactory(MethodHandles.Lookup caller, String name, MethodType type,
            MethodType samMethodType, MethodHandle implMethod, MethodType instantiatedMethodType) {
        return null;
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String name, MethodType type,
            Object... args) {
        return null;
    }
}
