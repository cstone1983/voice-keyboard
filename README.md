# Voice Keyboard

A personal Android keyboard: normal typing (swipe, suggestions, autocorrect) plus dictation through
**your own server** — Whisper speech-to-text and a local AI model that cleans the text up.

It is a fork of [Dictate Keyboard](https://github.com/DevEmperor/Dictate) by DevEmperor, which is a
fork of [FlorisBoard](https://github.com/florisboard/florisboard). Both are Apache-2.0; see
[LICENSE](LICENSE) and [NOTICE](NOTICE). Upstream's README is kept as
[README.upstream.md](README.upstream.md).

## What is different from Dictate

- Talks to your own OpenAI-compatible server (`/v1/audio/transcriptions`, `/v1/chat/completions`);
  the server URL and API key are entered in the app, never in the code.
- No Google Play Billing and no Dictate Cloud.
- API keys are encrypted on the phone with the Android Keystore.
- "Classic" themes with a familiar flat look (Material You colours by default), swipe typing on.
- Release builds are made and signed by [cstone1983/app-builder](https://github.com/cstone1983/app-builder);
  the phone installs and updates them with Obtainium.

Details, decisions and status: [docs/fork-notes.md](docs/fork-notes.md).

## Install

Installs and updates come through [Obtainium](https://github.com/ImranR98/Obtainium) from the
[cstone1983/app-builder releases](https://github.com/cstone1983/app-builder/releases) titled
"voice-keyboard <version>" (pre-releases; this repo no longer publishes its own):

1. In Obtainium, add `https://github.com/cstone1983/app-builder`.
2. Turn on "Include prereleases", and set the release title filter to `^voice-keyboard ` so only this
   app's builds are picked up (the builder publishes other apps too).
3. Install. Obtainium then offers each new build as an update.

Versions are `<app version>.<builder run>` (e.g. `6.4.0.12`).

**Coming from an older build of this repo** (`build-100xxxx`): those were signed with a different key,
so Android will not update them. Sync your profile first (Settings → Voice Keyboard → Sync my profile
now), uninstall the old app, then install from Obtainium; your words, fixes, touch habits and settings
come back from your server after you scan its QR code again.

Without Obtainium: `tools/install-latest.sh` installs the newest app-builder build over adb.

## Build

Needs JDK 21 and the Android SDK (platform 36, build-tools 36.0.0, NDK and CMake versions from
`gradle/tools.versions.toml`).

```bash
tools/fetch-sherpa-onnx.sh                 # on-device speech libraries (checksummed download)
tools/bergamot/build-android.sh arm64-v8a  # offline translation library
./gradlew :app:assembleDebug
```

`./gradlew :app:assembleRelease` builds an unsigned release APK when there is no `keystore.properties`;
that is how app-builder builds it before signing it with the shared key. A local signed build can
still read an untracked `keystore.properties` (see `keystore.properties.template`). Never commit keys.

CI (`.github/workflows/build.yml`) runs the unit tests and the unsigned release build on every push and
publishes nothing. `projectVersionName` must stay plain dotted numbers (e.g. `6.4.0`); the build
refuses anything else.
