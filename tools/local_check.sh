#!/usr/bin/env bash
# Offline check of the whole app: compiles the vendored Live2D framework, the app sources (against
# the Core AAR and API stubs of the two tracking libraries) and finally runs the unit tests on a
# plain JVM. It is the fast feedback loop; the GitHub workflow builds the real APK and runs the app
# on an emulator.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${TOOLCHAIN:-/tmp/echidna-toolchain}"
JAVA="$TOOLCHAIN/jre/bin/java"
JAVAC_CP="$TOOLCHAIN/tools.jar"
ANDROID_JAR="$TOOLCHAIN/android.jar"
OUT="${OUT:-/tmp/echidna-build}"

if [ ! -f "$TOOLCHAIN/tools.jar" ] || [ ! -f "$ANDROID_JAR" ]; then
  "$ROOT/tools/setup_toolchain.sh"
fi

mkdir -p "$OUT"
# The lambda stub makes javac able to emit invokedynamic against android.jar (see tools/stubs-java).
JAVAC=("$JAVA" -cp "$JAVAC_CP" com.sun.tools.javac.Main -nowarn -encoding UTF-8 -source 8 -target 8
       -bootclasspath "$ANDROID_JAR:$TOOLCHAIN/lambda-stub.jar")

compile() {          # compile <output dir> <sources file> <classpath>
  local out="$1" sources="$2" cp="$3"
  rm -rf "$out"; mkdir -p "$out"
  "${JAVAC[@]}" -cp "$cp" -d "$out" "@$sources"
}

echo "=== 0/6 проверка ресурсов и порядка запуска ==="
python3 "$ROOT/tools/check_resources.py" "$ROOT"
python3 "$ROOT/tools/check_startup_order.py" "$ROOT"

echo "=== 1/6 заглушки внешних библиотек ==="
find "$ROOT/tools/stubs" -name '*.java' > "$OUT/stubs.txt"
compile "$OUT/stubs" "$OUT/stubs.txt" "$JAVA"
cp -r "$ROOT/tools/stubs/." "$OUT/stubs-src" 2>/dev/null || true

echo "=== 2/6 распаковка Live2D Cubism Core ==="
rm -rf "$OUT/aar"; mkdir -p "$OUT/aar"
unzip -o -q "$ROOT/app/libs/Live2DCubismCore.aar" -d "$OUT/aar"
if [ ! -f "$OUT/aar/classes.jar" ]; then
  unzip -o -q "$ROOT/Live2DCubismCore.aar" -d "$OUT/aar"
fi

echo "=== 3/6 фреймворк Live2D ==="
find "$ROOT/app/src/main/java/com/live2d" -name '*.java' > "$OUT/framework.txt"
compile "$OUT/framework" "$OUT/framework.txt" "$OUT/aar/classes.jar"

echo "=== 4/6 код приложения ==="
find "$ROOT/app/src/main/java/com/echidna" -name '*.java' > "$OUT/app.txt"
compile "$OUT/app" "$OUT/app.txt" "$OUT/framework:$OUT/aar/classes.jar:$OUT/stubs"

echo "=== 5/6 юнит-тесты ==="
find "$ROOT/tools/junit-stub" "$ROOT/tools/android-stub" -name '*.java' > "$OUT/junit-stub.txt"
compile "$OUT/junit" "$OUT/junit-stub.txt" "$JAVA"
"${JAVAC[@]}" -cp "$OUT/junit" -d "$OUT/junit" "$ROOT/tools/LocalTestRunner.java"

find "$ROOT/app/src/test/java" -name '*.java' > "$OUT/tests.txt"
compile "$OUT/tests" "$OUT/tests.txt" \
  "$OUT/app:$OUT/framework:$OUT/aar/classes.jar:$OUT/stubs:$OUT/junit"

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$OUT/tests:$OUT/junit:$OUT/app:$OUT/framework:$OUT/aar/classes.jar:$OUT/stubs" \
  LocalTestRunner "$OUT/tests"

echo
echo "=== РЕЗУЛЬТАТ ==="
echo "классов приложения: $(find "$OUT/app" -name '*.class' | wc -l)"
echo "классов фреймворка: $(find "$OUT/framework" -name '*.class' | wc -l)"
echo "ОК"
