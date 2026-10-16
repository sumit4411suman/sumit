#!/usr/bin/env bash
# Standalone APK build that does not need the Android Gradle Plugin or an Android SDK installation.
# It compiles exactly the same sources as the Gradle project with: aapt2, kotlinc, javac, dx/d8, zipalign, apksigner.
#
# Requirements (Debian/Ubuntu): apt-get install aapt apksigner zipalign android-sdk-build-tools unzip zip
#   plus a Kotlin compiler (kotlinc on PATH or KOTLINC_HOME) and an android.jar for API 35 (ANDROID_JAR).
# Optional: D8_JAR pointing at a d8/r8 jar; otherwise the Debian dx is used.
#
# Usage: tools/build-apk.sh [--release-key path.keystore --key-alias alias --key-pass pass]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
BUILD="$ROOT/tools/.build"
DIST="$ROOT/dist"
KOTLINC="${KOTLINC:-${KOTLINC_HOME:+$KOTLINC_HOME/bin/kotlinc}}"
KOTLINC="${KOTLINC:-kotlinc}"
ANDROID_JAR="${ANDROID_JAR:?set ANDROID_JAR to an API 35 android.jar}"
# Older aapt2 builds (e.g. Debian 2.19) cannot parse the API 35 resources table; point this at an API 34 jar then.
AAPT2_LINK_JAR="${AAPT2_LINK_JAR:-$ANDROID_JAR}"
DX="${DX:-/usr/lib/android-sdk/build-tools/debian/dx}"
D8_JAR="${D8_JAR:-}"
MIN_SDK=29
TARGET_SDK=35
VERSION_CODE=100
VERSION_NAME=1.0.0
PKG=com.names26.productchanger

rm -rf "$BUILD"; mkdir -p "$BUILD"/{res,gen,classes,assets,apk} "$DIST"

echo "== 1/7 module zip -> asset"
"$ROOT/tools/build-module.sh" "$BUILD/assets/names26_module.zip" >/dev/null
cp -r "$APP/assets/." "$BUILD/assets/" 2>/dev/null || true

echo "== 2/7 aapt2 compile/link"
aapt2 compile --dir "$APP/res" -o "$BUILD/res/res.zip"
# AGP derives the package from build.gradle.kts; aapt2 needs it in the manifest.
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">|" \
  "$APP/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
aapt2 link -o "$BUILD/apk/base.apk" -I "$AAPT2_LINK_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" -A "$BUILD/assets" --java "$BUILD/gen" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  --compile-sdk-version-code 35 --compile-sdk-version-name 15 \
  --no-version-vectors "$BUILD/res/res.zip"

echo "== 3/7 javac (R.java)"
javac -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" -d "$BUILD/classes" "$BUILD/gen/${PKG//./\/}/R.java" 2>&1 | grep -v "^warning: \[options\]" || true

echo "== 4/7 kotlinc"
KOTLIN_LIB="$(dirname "$(command -v "$KOTLINC" || echo "$KOTLINC")")/../lib"
"$KOTLINC" -no-stdlib -no-reflect -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline \
  -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions \
  -classpath "$ANDROID_JAR:$BUILD/classes:$KOTLIN_LIB/kotlin-stdlib.jar" \
  -d "$BUILD/classes" "$APP/kotlin"

echo "== 5/7 dex"
# kotlin-stdlib is a multi-release jar; dx cannot read META-INF/versions/9/module-info.class, so dex a stripped copy.
STDLIB="$BUILD/kotlin-stdlib-stripped.jar"
cp "$KOTLIN_LIB/kotlin-stdlib.jar" "$STDLIB"
zip -q -d "$STDLIB" 'META-INF/versions/*' 'module-info.class' 'META-INF/*.kotlin_module' 'META-INF/MANIFEST.MF' 2>/dev/null || true
if [ -n "$D8_JAR" ]; then
  java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api $MIN_SDK --lib "$ANDROID_JAR" \
    --output "$BUILD/apk" "$STDLIB" $(find "$BUILD/classes" -name '*.class')
else
  "$DX" --dex --min-sdk-version=$MIN_SDK --output="$BUILD/apk/classes.dex" "$BUILD/classes" "$STDLIB"
fi

echo "== 6/7 package + zipalign"
( cd "$BUILD/apk" && zip -q -X -D base.apk classes.dex )
zipalign -p -f 4 "$BUILD/apk/base.apk" "$BUILD/apk/aligned.apk"

echo "== 7/7 sign"
KS="${RELEASE_KEYSTORE:-$ROOT/tools/keystore/names26.keystore}"
ALIAS="${KEY_ALIAS:-names26}"
PASS="${KEY_PASS:-names26-local}"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -storepass "$PASS" -keypass "$PASS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NameS26 local build, O=NameS26" >/dev/null 2>&1
  echo "generated signing key $KS (keep it to be able to update the app in place)"
fi
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$DIST/NameS26.apk" "$BUILD/apk/aligned.apk"
apksigner verify --print-certs "$DIST/NameS26.apk" | head -3
cp "$BUILD/assets/names26_module.zip" "$DIST/NameS26-magisk-module.zip"
( cd "$DIST" && sha256sum NameS26.apk NameS26-magisk-module.zip | tee SHA256SUMS.txt )
echo "done: $DIST/NameS26.apk"
