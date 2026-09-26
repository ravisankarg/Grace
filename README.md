# Grace

Grace is a private, local-first Android companion for daily spiritual practice and calm, role-aware meetings.

## Grace 0.3.0

This version brings the focused parts of Meeting Anchor into Grace and removes the Settings page, Gemma 4 E4B, EmbeddingGemma, model downloads, native embedding code, and the packaged scripture index.

Download the signed APK from the [Grace v0.3.0 release](https://github.com/ravisankarg/Grace/releases/tag/v0.3.0), or use the direct [Grace-v0.3.0-release.apk](https://github.com/ravisankarg/Grace/releases/download/v0.3.0/Grace-v0.3.0-release.apk) link.

Release APK SHA-256: `e119a548bcaa39b91d27eae03c534c793c067a0b6306c9aa16d50f6d23a9df61`

## What the app does

- Holds four daily anchors: remember before sunrise, spend time in stillness, keep inner freedom, and do the one thing that would matter if today were the last day.
- Lets the user write or dictate private reflections.
- Provides 10–60 minute meditation timers with real rain, fire crackling, and shruti box audio.
- Keeps a local Journey view of daily practice and reflections.
- Adds a Meetings area with two immediate modes: **With team** and **Level up**.
- Guides both meeting modes through the same core flow: do not react, choose the outcome, respond from the role, keep ownership clear, and give the right next action.
- Adds Level up anchors for role boundaries, the right owner, an honest “I don’t know,” Point → Proof → Path, and a speakable stop rule.

There is no meeting calendar, session timer, start/stop control, account, network permission, model installer, or cloud service.

## Private by design

Practice records and reflections remain in the app’s private storage. Grace does not request internet access. Version 0.3.0 also removes old downloaded model files and their download/index status on first launch after an upgrade. Existing daily records and reflections are preserved.

## Updating without losing phone data

Grace remains `com.ravi.grace`. Install over the existing app with the same signing key:

```bash
adb -s DEVICE_SERIAL install -r -d Grace-v0.3.0-release.apk
```

Do not uninstall or clear app data when the phone contains real practice records.

## Build

The project uses Android SDK 34, Java 17, and the included Gradle wrapper:

```bash
./gradlew :app:assembleDebug --console=plain
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Source

Grace is published for personal, local-first experimentation. The bundled sounds and portrait retain their respective owners’ licenses.
