#!/usr/bin/env bash
# Compiles and runs EnhanceRunner.java against the app's already-compiled AutoEnhance class,
# so tuning changes can be checked against real full-resolution sample photos without an
# emulator and without a separate reimplementation to keep in sync (see EnhanceRunner.java for
# why this isn't a plain `gradlew test`).
#
# Usage: after any AutoEnhance.java change, run `gradlew test` or
# `gradlew bundleDebugClassesToCompileJar` first so classes.jar is current - assembleDebug alone
# does NOT rebuild it (it's produced as a side effect of the unit test task graph, not the APK
# build). If a change doesn't seem to show up here, the Gradle daemon's file watcher can
# occasionally miss an edit - run `gradlew --stop` once and retry. Then:
#   enhance_tuning/render.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-C:\Program Files\Android\Android Studio\jbr}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
SDK_DIR="$(grep '^sdk.dir=' local.properties | cut -d= -f2-)"
ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
APP_CLASSES_JAR="app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar"
OUT_DIR="enhance_tuning/out"

if [ ! -f "$APP_CLASSES_JAR" ]; then
    echo "Missing $APP_CLASSES_JAR - run gradlew assembleDebug or gradlew test first." >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
"$JAVAC" -cp "$APP_CLASSES_JAR;$ANDROID_JAR" -d "$OUT_DIR" enhance_tuning/EnhanceRunner.java
"$JAVA" -cp "$OUT_DIR;$APP_CLASSES_JAR;$ANDROID_JAR" com.rawviewergo.EnhanceRunner enhance_tuning
