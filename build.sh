#!/usr/bin/env bash
# =============================================================================
#  settings-app-freezer — build script
#
#  Manual build pipeline (no Gradle, no Android Studio required):
#      javac -> jar -> d8 -> aapt2 link -> inject classes.dex -> zipalign -> apksigner
#
#  Toolchain locations are resolved in this order:
#      1. environment variables (JDK_HOME / ANDROID_SDK_ROOT)
#      2. toolchain root guessed from this script's location (../ relative)
#      3. keys read from keystore.properties (git-ignored) or debug defaults
#
#  Usage:  bash build.sh
# =============================================================================
set -u

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- Version (single source of truth: version.properties) -------------------
if [ -f "$SRC/version.properties" ]; then
  # shellcheck disable=SC1091
  . "$SRC/version.properties"
else
  echo "FATAL: version.properties not found"; exit 1
fi
: "${VERSION_NAME:=1.0.0}"
: "${VERSION_CODE:=100}"

# AndroidManifest.xml carries the version that actually lands in the APK (aapt2 link is not
# given --version-code/--version-name), while version.properties is the documented single source
# of truth. Nothing else keeps the two in sync, so a silent drift ships an APK whose version
# disagrees with the repo. Fail the build instead.
_MANIFEST="$SRC/AndroidManifest.xml"
if [ -f "$_MANIFEST" ]; then
  _M_CODE="$(grep -oE 'android:versionCode="[^"]*"' "$_MANIFEST" | head -1 | grep -oE '[0-9]+')"
  _M_NAME="$(grep -oE 'android:versionName="[^"]*"' "$_MANIFEST" | head -1 | sed 's/.*="//; s/"$//')"
  if [ "$_M_CODE" != "$VERSION_CODE" ] || [ "$_M_NAME" != "$VERSION_NAME" ]; then
    echo "FATAL: version mismatch between version.properties and AndroidManifest.xml"
    echo "       version.properties : code=$VERSION_CODE name=$VERSION_NAME"
    echo "       AndroidManifest.xml: code=$_M_CODE name=$_M_NAME"
    exit 1
  fi
  echo "version OK: $VERSION_NAME ($VERSION_CODE)"
fi

# ---- Toolchain resolution ---------------------------------------------------
# Expected layout (override with env vars if yours differs):
#   <root>/jdk/                      JDK 17
#   <root>/android-sdk/               Android SDK (platform-36 + build-tools)
#   <root>/lib/api-82.jar             LSPosed Xposed API stubs
#   <root>/debug.keystore             signing key (git-ignored)
GUESS_ROOT="$(cd "$SRC/.." && pwd)"

JDK_HOME="${JDK_HOME:-}"
if [ -z "$JDK_HOME" ]; then
  for cand in "$GUESS_ROOT/jdk" "/c/android-build/jdk"; do
    [ -x "$cand/bin/javac" ] && JDK_HOME="$cand" && break
  done
fi
[ -z "$JDK_HOME" ] && { echo "FATAL: JDK 17 not found. Set JDK_HOME=/path/to/jdk17"; exit 1; }

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-}"
if [ -z "$ANDROID_SDK_ROOT" ]; then
  for cand in "$GUESS_ROOT/android-sdk" "/c/android-build/android-sdk"; do
    [ -d "$cand/platforms" ] && ANDROID_SDK_ROOT="$cand" && break
  done
fi
[ -z "$ANDROID_SDK_ROOT" ] && { echo "FATAL: Android SDK not found. Set ANDROID_SDK_ROOT=/path/to/sdk"; exit 1; }

API="${API:-android-36}"
PLATFORM="$ANDROID_SDK_ROOT/platforms/$API/android.jar"
[ -f "$PLATFORM" ] || { echo "FATAL: missing $PLATFORM"; exit 1; }

BT=""
for cand in "$ANDROID_SDK_ROOT"/build-tools/*/; do
  [ -f "${cand}aapt2.exe" ] || [ -f "${cand}aapt2" ] || continue
  [ -z "$BT" ] && BT="$cand"
done
[ -n "$BT" ] || { echo "FATAL: no usable build-tools under $ANDROID_SDK_ROOT/build-tools"; exit 1; }
BT="${BT%/}"

APIJAR="${XPOSED_API_JAR:-$GUESS_ROOT/lib/api-82.jar}"
[ -f "$APIJAR" ] || { echo "FATAL: missing Xposed API jar: $APIJAR"; exit 1; }

JAVA="$JDK_HOME/bin/java"
JAVAC="$JDK_HOME/bin/javac"
JAR="$JDK_HOME/bin/jar"

# ---- Python (for classes.dex injection) -------------------------------------
PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || PYTHON=python
command -v "$PYTHON" >/dev/null 2>&1 || { echo "FATAL: python3 is required"; exit 1; }
# On MSYS/Git-Bash the interpreter may be a native Windows build that cannot read
# POSIX paths, so convert every path handed to it.
wpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# ---- Signing key (never committed) ------------------------------------------
KS="$GUESS_ROOT/debug.keystore"
KS_ALIAS="androiddebugkey"
KS_PASS="android"
if [ -f "$SRC/keystore.properties" ]; then
  # shellcheck disable=SC1091
  . "$SRC/keystore.properties"
  KS="${KEYSTORE_PATH:-$KS}"
  KS_ALIAS="${KEYSTORE_ALIAS:-$KS_ALIAS}"
  KS_PASS="${KEYSTORE_PASS:-$KS_PASS}"
fi

OUT="$SRC/out"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
FINAL="$SRC/freezer-module.apk"

rm -rf "$CLASSES" "$DEX" "$OUT/classes.jar" "$OUT/base.apk" \
       "$OUT/unsigned.apk" "$OUT/aligned.apk"
mkdir -p "$CLASSES"

echo "== settings-app-freezer v$VERSION_NAME (code $VERSION_CODE) =="

echo "== compile =="
"$JAVAC" -encoding UTF-8 -source 17 -target 17 -cp "$PLATFORM:$APIJAR" -d "$CLASSES" \
    "$SRC/src/io/github/zhangdunlong/settingsappfreezer/FreezerModule.java" 2>&1 \
  || { echo "FATAL: javac failed - aborting before packaging"; exit 1; }

echo "== jar classes =="
"$JAR" cf "$OUT/classes.jar" -C "$CLASSES" .

echo "== d8 -> classes.dex =="
mkdir -p "$DEX"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 26 --output "$DEX" "$OUT/classes.jar" \
  || { echo "FATAL: d8 failed"; exit 1; }

echo "== aapt2 link (manifest + xposed_init asset) =="
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$PLATFORM" \
    --manifest "$SRC/AndroidManifest.xml" \
    -A "$SRC/assets" \
  || { echo "FATAL: aapt2 link failed"; exit 1; }

echo "== inject classes.dex =="
"$PYTHON" "$SRC/lib/add_dex.py" "$(wpath "$OUT/base.apk")" \
    "$(wpath "$DEX/classes.dex")" "$(wpath "$OUT/unsigned.apk")" \
  || { echo "FATAL: dex injection failed"; exit 1; }

echo "== zipalign =="
"$BT/zipalign.exe" -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk" \
  || { echo "FATAL: zipalign failed"; exit 1; }

echo "== sign =="
if [ ! -f "$KS" ]; then
  echo "-- generating debug keystore at $KS (git-ignored)"
  "$JDK_HOME/bin/keytool" -genkeypair -v \
    -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Android Debug,O=Android,C=US" \
    || { echo "FATAL: keytool failed"; exit 1; }
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$FINAL" "$OUT/aligned.apk" \
  || { echo "FATAL: apksigner failed"; exit 1; }

echo "== verify =="
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --verbose "$FINAL" | head -5
echo "--- manifest versionName in APK ---"
"$PYTHON" - "$FINAL" <<'PY'
import sys, zipfile, re
z = zipfile.ZipFile(sys.argv[1])
m = z.read('assets/xposed_init').decode('utf-8', 'replace').strip()
print('xposed_init =', m)
print('entries     =', [n for n in z.namelist()
                        if n in ('classes.dex', 'assets/xposed_init')])
PY
echo
echo "BUILD DONE -> $FINAL  (v$VERSION_NAME)"
