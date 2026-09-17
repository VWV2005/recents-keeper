#!/usr/bin/env bash
# Builds RecentsKeeper with the plain Android build-tools, no Gradle required.
#
# Requires: a JDK (javac, jar, keytool), an Android SDK with build-tools and at
# least one platform, and Python 3 (used to repack the APK).
#
# SDK discovery order: $ANDROID_HOME, $ANDROID_SDK_ROOT, local.properties.
# Overridable: BUILD_TOOLS, ANDROID_JAR, XPOSED_JAR, RK_KEYSTORE,
#              RK_KEYSTORE_PASS, RK_KEY_ALIAS, RK_KEY_PASS
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# javac/d8 are native binaries: on Windows they need C:/... paths, not /c/...
# cygpath only exists in Git Bash, hence the fallback.
ROOT="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; BAT=".bat" ;;
  *)                    EXE="";     BAT="" ;;
esac

# BSD sort has no -V, so fall back to a plain lexical sort there.
if sort -V </dev/null >/dev/null 2>&1; then SORTV="sort -V"; else SORTV="sort"; fi

# Pick a Python that actually runs: on Windows, `python3` often resolves to the
# Microsoft Store stub, which is not an interpreter and fails when executed.
PYTHON=""
for candidate in python3 python; do
  path="$(command -v "$candidate" 2>/dev/null || true)"
  [ -n "$path" ] || continue
  if "$path" -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
    PYTHON="$path"
    break
  fi
done
if [ -z "$PYTHON" ]; then
  echo "error: python 3 is required (repacks the APK after dexing)" >&2
  exit 1
fi

# --------------------------------------------------------------- SDK discovery
# Only needed when BUILD_TOOLS / ANDROID_JAR are not given explicitly.
if [ -z "${SDK:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ]; then
    SDK="$ANDROID_HOME"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    SDK="$ANDROID_SDK_ROOT"
  elif [ -f "$ROOT/local.properties" ]; then
    SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1)"
  fi
fi
[ -n "${SDK:-}" ] && SDK="$(cygpath -m "$SDK" 2>/dev/null || echo "$SDK")"

# --------------------------------------------------- build-tools and platform
if [ -z "${BUILD_TOOLS:-}" ]; then
  BUILD_TOOLS="$(find "${SDK:-/nonexistent}/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | $SORTV | tail -1)"
fi
if [ -z "${BUILD_TOOLS:-}" ] || [ ! -d "$BUILD_TOOLS" ]; then
  echo "error: build-tools not found. Set ANDROID_HOME, add sdk.dir to" >&2
  echo "       local.properties, or point BUILD_TOOLS at a build-tools directory." >&2
  exit 1
fi

if [ -z "${ANDROID_JAR:-}" ]; then
  ANDROID_JAR="$(find "${SDK:-/nonexistent}/platforms" -maxdepth 2 -name android.jar 2>/dev/null | $SORTV | tail -1)"
fi
if [ -z "${ANDROID_JAR:-}" ] || [ ! -f "$ANDROID_JAR" ]; then
  echo "error: android.jar not found. Set ANDROID_HOME, add sdk.dir to" >&2
  echo "       local.properties, or point ANDROID_JAR at a platform android.jar." >&2
  exit 1
fi

XPOSED_JAR="${XPOSED_JAR:-$ROOT/libs/xposed-api-82.jar}"
if [ ! -f "$XPOSED_JAR" ]; then
  echo "error: Xposed API jar not found: $XPOSED_JAR" >&2
  exit 1
fi

echo "sdk      : ${SDK:-<not used, explicit tool paths given>}"
echo "tools    : $BUILD_TOOLS"
echo "platform : $ANDROID_JAR"

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== aapt2 compile =="
"$BUILD_TOOLS/aapt2$EXE" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "== aapt2 link =="
"$BUILD_TOOLS/aapt2$EXE" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  -A "$ROOT/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "== javac =="
find "$ROOT/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$XPOSED_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "== package classes =="
( cd "$OUT/classes" && jar cf "$OUT/classes.jar" . )

echo "== d8 =="
"$BUILD_TOOLS/d8$BAT" --lib "$ANDROID_JAR" --min-api 29 --output "$OUT/dex" "$OUT/classes.jar"

echo "== repack =="
"$PYTHON" "$ROOT/mkapk.py" "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk"

echo "== zipalign =="
"$BUILD_TOOLS/zipalign$EXE" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# -------------------------------------------------------------------- signing
# Credentials come from the environment or from a git-ignored
# keystore.properties. Without them the APK is signed with a throwaway debug
# key, so that a plain clone still produces something installable.
KS="${RK_KEYSTORE:-}"
KS_PASS="${RK_KEYSTORE_PASS:-}"
KEY_ALIAS="${RK_KEY_ALIAS:-}"
KEY_PASS="${RK_KEY_PASS:-}"
if [ -z "$KS" ] && [ -f "$ROOT/keystore.properties" ]; then
  KS="$(sed -n 's/^storeFile=//p' "$ROOT/keystore.properties" | tail -1)"
  KS_PASS="$(sed -n 's/^storePassword=//p' "$ROOT/keystore.properties" | tail -1)"
  KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$ROOT/keystore.properties" | tail -1)"
  KEY_PASS="$(sed -n 's/^keyPassword=//p' "$ROOT/keystore.properties" | tail -1)"
fi

SIGN_ARGS=()
if [ -n "$KS" ]; then
  if [ ! -f "$KS" ]; then
    echo "error: keystore not found: $KS" >&2
    exit 1
  fi
  SIGN_ARGS+=(--ks "$KS" --ks-pass "pass:$KS_PASS")
  [ -n "$KEY_ALIAS" ] && SIGN_ARGS+=(--ks-key-alias "$KEY_ALIAS")
  [ -n "$KEY_PASS" ] && SIGN_ARGS+=(--key-pass "pass:$KEY_PASS")
  echo "== sign (release key) =="
else
  KS="$OUT/debug.keystore"
  if [ ! -f "$KS" ]; then
    echo "== generating throwaway debug keystore =="
    keytool -genkeypair -keystore "$KS" -alias debug -keyalg RSA -keysize 2048 \
      -validity 10000 -storepass android -keypass android \
      -dname "CN=RecentsKeeper debug" >/dev/null 2>&1
  fi
  SIGN_ARGS=(--ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias debug)
  echo "== sign (debug key; set keystore.properties for a real release) =="
fi

"$BUILD_TOOLS/apksigner$BAT" sign "${SIGN_ARGS[@]}" \
  --out "$ROOT/RecentsKeeper.apk" "$OUT/aligned.apk"

echo
echo "OK -> $ROOT/RecentsKeeper.apk"
