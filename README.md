# RawViewerGo

Android app for viewing camera raw files on the go (e.g. from a USB-C SD card reader) - built for personal use with the Kodak Pro Back (`.dcr`), Mamiya ZD (`.mef`), and Phase One (`.iiq`) digital backs.

## What it does

- **Browse screen**: pick a folder (SD card, USB reader, or local storage) via Android's Storage Access Framework, shows a thumbnail grid of `.dcr`/`.mef`/`.iiq` files.
- **Viewer screen**: full-resolution decode of the selected raw file.
- **Auto Enhance**: toggle on either screen for an auto brightness/contrast stretch + saturation boost + sharpen, applied as a post-process so toggling doesn't require re-decoding. Kodak and Mamiya files get extra format-specific color/tone tuning (see `AutoEnhance.java`); other formats (e.g. Phase One) get the general boost only.

## Technology

- Java , traditional Android Views with XML layouts (no Compose)
- Native raw decoding via [LibRaw](https://github.com/LibRaw/LibRaw) 0.22.2, vendored under `app/src/main/cpp/libraw` and built with our own JNI bridge (`app/src/main/cpp/raw_jni.cpp`) through NDK + CMake
- A reused prebuilt LibRaw binary (from a decompiled reference app, `com.anthonymandra.dcraw.LibRaw`) is kept as a fallback path for embedded-thumbnail extraction
- Gradle (Groovy DSL), Android Gradle Plugin, targets `minSdk 26`

## Building the APK

From the project root, using Android Studio's bundled JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

This also copies the APK to `build\RawViewerGo.apk` (a stable path for sideloading onto a phone, not tracked in git).

## Running the unit tests

`AutoEnhance`'s tone/color/sharpen logic works on plain `int[]` pixel arrays with no `android.graphics` dependency, so it's covered by plain JVM unit tests - no emulator or Robolectric needed:

```powershell
.\gradlew.bat test
```

## Running in the emulator

Start the emulator (AVD `Medium_Phone_API_36`):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_36
```

Once booted, install and launch the app:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "build\RawViewerGo.apk"
& $adb shell am start -n com.rawviewergo/.BrowseActivity
```
