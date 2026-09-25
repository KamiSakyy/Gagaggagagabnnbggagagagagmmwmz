#!/usr/bin/env bash
# Downloads the pieces the offline local check needs:
#   * a JDK runtime (jdk4py from PyPI - the sandbox has PyPI access but no java)
#   * javac 8 (packaged inside an npm tarball)
#   * android.jar for API 34 (from a GitHub mirror of the Android platform)
# Everything lands in $TOOLCHAIN (default /tmp/echidna-toolchain) and is safe to re-run.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${TOOLCHAIN:-/tmp/echidna-toolchain}"
mkdir -p "$TOOLCHAIN"
cd "$TOOLCHAIN"

# ---------------------------------------------------------------- java runtime
if [ ! -x "$TOOLCHAIN/jre/bin/java" ]; then
  echo "== java runtime =="
  pip download --no-deps --dest "$TOOLCHAIN/dl" jdk4py >/dev/null
  pip install --quiet --break-system-packages "$TOOLCHAIN"/dl/jdk4py-*.whl >/dev/null
  JRE_HOME="$(python3 -c 'import jdk4py; print(jdk4py.JAVA_HOME)')"
  ln -sfn "$JRE_HOME" "$TOOLCHAIN/jre"
fi
"$TOOLCHAIN/jre/bin/java" -version

# ---------------------------------------------------------------------- javac
if [ ! -f "$TOOLCHAIN/tools.jar" ]; then
  echo "== javac 8 =="
  ( cd "$TOOLCHAIN" && npm pack dataslope-tools-jar >/dev/null && tar xzf dataslope-tools-jar-*.tgz )
  cp "$TOOLCHAIN/package/tools.jar" "$TOOLCHAIN/tools.jar"
fi

# ------------------------------------------------------------------ android.jar
if [ ! -f "$TOOLCHAIN/android.jar" ]; then
  echo "== android.jar (API 34) =="
  gh api "repos/Sable/android-platforms/contents/android-34/android.jar" \
    -H "Accept: application/vnd.github.raw" > "$TOOLCHAIN/android.jar"
fi
# ------------------------------------------------- bootclasspath lambda stub
if [ ! -f "$TOOLCHAIN/lambda-stub.jar" ]; then
  echo "== lambda stub =="
  rm -rf "$TOOLCHAIN/lambda-stub"
  mkdir -p "$TOOLCHAIN/lambda-stub"
  "$TOOLCHAIN/jre/bin/java" -cp "$TOOLCHAIN/tools.jar" com.sun.tools.javac.Main \
    -nowarn -source 8 -target 8 -bootclasspath "$TOOLCHAIN/android.jar" \
    -d "$TOOLCHAIN/lambda-stub" "$ROOT/tools/stubs-java/java/lang/invoke/LambdaMetafactory.java"
  ( cd "$TOOLCHAIN/lambda-stub" && python3 -c "
import zipfile
z = zipfile.ZipFile('$TOOLCHAIN/lambda-stub.jar', 'w')
z.write('java/lang/invoke/LambdaMetafactory.class')
z.close()
" )
fi

ls -la "$TOOLCHAIN/tools.jar" "$TOOLCHAIN/android.jar" "$TOOLCHAIN/lambda-stub.jar"
echo "инструментарий готов: $TOOLCHAIN"
