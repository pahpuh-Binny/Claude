# Text Editor

A basic text editor for Android that opens any file. It is a small native app (about 30 KB) with no ads, no permissions and no network access.

## What it does

- **Opens any file** through Android's file picker, or from another app's **Open with** menu.
- **Text files** (JSON, HTML, XML, CSV, Markdown, code, logs…) open as text in a monospace font. UTF-8 and UTF-16 are detected, and the file's byte order mark and line endings (LF or CRLF) are kept when you save.
- **Binary files** (images, archives, executables…) open as **editable hex**. Each line shows an offset, 8 bytes and a text preview:

  ```
  00000000: 89 50 4E 47 0D 0A 1A 0A | .PNG....
  ```

  Edit, add or delete the hex bytes between `:` and `|`. The offset and the preview are ignored when saving, so you don't need to keep them in step.
- **Hex view** in the menu switches any file between text and hex.
- **Save** writes back to the same file; **Save as…** writes a copy. If a file is read-only (common when it was shared from another app), the editor offers to save a copy.
- **Find** searches the file, ignoring case. **Word wrap** turns line wrapping on or off. **Format JSON** re-indents JSON without changing its values or key order.
- Follows the system light or dark theme.

## Limits

- Text files larger than 1 MB and binary files larger than 64 KB open as a **read-only preview** of their first part, because Android's text box gets slow with more.
- There is no syntax colouring, and one file is open at a time.
- Unsaved changes are lost if Android closes the app in the background, so save before switching away for long.

## Install on Android

1. Get `app-release.apk`. Open this repository's **Actions** tab, pick the latest **Text Editor APK** run, and download the `TextEditor-apk` artifact (a zip with the APK inside).
2. Open the APK on your phone. Android asks you to allow installs from that app (for example Chrome or Files). Allow it, then tap **Install**.
3. Open **Text Editor** from your app drawer.

The app needs Android 8.0 or newer.

## Build the APK yourself

You need JDK 17 or newer and the Android SDK (platform 35 and build-tools 35).

```sh
cd text-editor
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest assembleRelease
# APK: text-editor/app/build/outputs/apk/release/app-release.apk
```

## Code

- `app/src/main/java/.../MainActivity.java` is the screen: opening, saving, the menu and find.
- `app/src/main/java/.../FileCodec.java` converts file bytes to text or hex and back. It has no Android dependencies and is covered by `app/src/test/.../FileCodecTest.java`.
