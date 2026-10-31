# D60 Link

🇪🇸 [Versión en español](README.es.md)

Android app (Material 3) to browse and download the photos on a Nikon D60 (or any
PTP camera) over a USB OTG cable, without going through a computer. UI in English
and Spanish (follows the device language).

## Features

- Opens by itself when the camera is plugged in.
- Gallery built from the camera's own thumbnails, grouped by day.
- Marks the photos you already downloaded, with "All / New" filters.
- Multiple selection (long press) or a whole day at once.
- "Download new" in one tap. Saves to `DCIM/NikonD60` through MediaStore.
- Zoomable preview before downloading (NEF files show their thumbnail).
- Material 3 with dynamic colors.

## Usage

1. On the camera: **Setup menu › USB › MTP/PTP**.
2. OTG cable **Mini-USB (Mini-B) male → USB-C male**.
3. Turn the camera on, plug it in and accept the USB permission.

## Building

Requirements: JDK 17+, Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease
```

The APK ends up in `app/build/outputs/apk/release/`. The release build is signed with the debug key.

## Structure

- `Camera.kt`: PTP access through `android.mtp.MtpDevice` (single thread), thumbnails, preview and download to MediaStore.
- `MainViewModel.kt`: state, selection, record of downloaded photos.
- `MainActivity.kt`: USB detection, permissions, connect and disconnect.
- `Ui.kt` / `Theme.kt`: Jetpack Compose UI.
- `res/values` (English) and `res/values-es` (Spanish): UI strings.

## Still to verify with the real camera

- Detection in MTP/PTP mode, and the warning in mass storage mode.
- Photo dates.
- Whether MediaStore accepts NEF files (`image/x-nikon-nef`).
