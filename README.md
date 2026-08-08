# Grace

Grace is a private, local-first daily companion for a spiritual seeker. It is deliberately a practice app, not a scorekeeper: an honest return is recorded as progress.

## Grace 0.2.8

This release fixes the public model installer for Android 14–16 devices and makes meditation ambience loop continuously with a simple single-player fade-out/fade-in boundary. It removes long-running successor-player allocation and release operations that could eventually stop sound. It declares the WorkManager data-sync foreground service correctly, starts foreground download work before opening the network stream, supports metered Wi-Fi without getting stuck in the queue, validates resumed ranges, verifies both models with SHA-256, detects insufficient storage, and preserves resumable `.part` files.

Download the signed APK from the [Grace v0.2.8 release](https://github.com/ravisankarg/Grace/releases/tag/v0.2.8), or use the direct [Grace-v0.2.8-release.apk](https://github.com/ravisankarg/Grace/releases/download/v0.2.8/Grace-v0.2.8-release.apk) link.

Release APK SHA-256: `a1b3c33a0fb936424eabf0bf3ef2deb955f923eebc2a3150f29ef48b1477fa85`

See the visual project story on [Grace GitHub Pages](https://ravisankarg.github.io/Grace/).

## What the app does

- Shows a calm daily practice page with before-sunrise, morning meditation, evening meditation, and inner-freedom check-ins.
- Lets the seeker write or dictate private reflections.
- Uses Gemma 4 E4B locally to label each new reflection and give a brief inward-looking response.
- Matches each reflection against the bundled scripture index using local EmbeddingGemma query encoding.
- Shows source passages, page numbers, cosine scores, and the saved guidance in the Journey view.
- Provides 10–60 minute meditation timers with Real rain, Fire crackling, and Shruti box audio.
- Keeps daily records and reminders on the device.

## Private and offline by design

Grace keeps reflections, practice records, model files, and settings in the app's private storage. The source PDFs are processed on the laptop before the APK is built; the APK carries only the finished vector index. Retrieval on the phone encodes the private query and searches the packaged index locally.

The app does not embed a Hugging Face token and does not ask friends for one. The Settings installer uses revision-pinned public files:

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| EmbeddingGemma 300M Q8_0 | 333,590,944 bytes | `b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63` |
| Gemma 4 E4B IT LiteRT | 3,659,530,240 bytes | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |

The installer downloads EmbeddingGemma first, then Gemma 4 E4B. It uses a foreground notification, keeps partial files, safely restarts when a CDN ignores or misreports a byte range, and retries transient failures. HTTP 4xx errors and insufficient storage are shown as actionable failures.

## Updating without losing phone data

Grace remains `com.ravi.grace` in 0.2.8. The update does not uninstall the app, clear data, rename its SharedPreferences, or remove the `filesDir` model/index paths. Installing the new APK over an existing installation preserves logged reflections, Journey history, meditation state, reminders, and downloaded models, provided the APK is signed with the same signing key.

For a developer sideload, use an update install:

```bash
adb -s DEVICE_SERIAL install -r -d Grace-v0.2.8-release.apk
```

Do not use `adb uninstall`, `pm clear`, or a fresh install when the phone contains real practice data. The published APK is debug-keystore signed for sideload/testing; it is not a Play Store signing key.

## Build

The local toolchain uses Android SDK 34 and Gradle 8.4:

```bash
gradle :app:assembleDebug --console=plain
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

For the release handoff, build the unsigned release APK, align it, sign it with the existing sideload key, verify its certificate, and record its SHA-256:

```bash
gradle :app:assembleRelease --console=plain
zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/Grace-v0.2.8-aligned.apk
apksigner sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --out app/build/outputs/apk/release/Grace-v0.2.8-release.apk \
  /tmp/Grace-v0.2.8-aligned.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/release/Grace-v0.2.8-release.apk
sha256sum app/build/outputs/apk/release/Grace-v0.2.8-release.apk
```

## Rebuild the bundled source index

Use the exact `embeddinggemma-300M-Q8_0.gguf` installed on the phone, copied to the laptop only for the build:

```bash
python3 tools/build_offline_scripture_index.py --model /path/to/embeddinggemma-300M-Q8_0.gguf
gradle :app:assembleDebug --console=plain
```

The script extracts the three books page by page, creates 512-word passages with 48-word overlap, mean-pools the EmbeddingGemma vectors, and writes `app/src/main/assets/indexes/scripture-index-v3.jsonl`. The GGUF is never added to the repository or APK.

## Validation

Useful checks before a handoff:

```bash
gradle :app:lintDebug --console=plain
gradle :app:assembleRelease --console=plain
apkanalyzer manifest permissions app/build/outputs/apk/release/Grace-v0.2.8-release.apk
unzip -l app/build/outputs/apk/release/Grace-v0.2.8-release.apk | rg 'AndroidManifest|libgrace_embedding|scripture-index-v3'
```

The release manifest must contain `FOREGROUND_SERVICE_DATA_SYNC`, and WorkManager's `SystemForegroundService` must declare `dataSync`. The app must retain the same package name and signing certificate for an in-place update.

## License and source

Grace is published in this repository for personal, local-first experimentation. The supplied books, sounds, portrait, and model artifacts retain their respective owners' licenses. Grace does not redistribute the multi-gigabyte model files; it downloads the pinned public artifacts directly from their public hosts.
