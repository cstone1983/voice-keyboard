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
- Built and signed by GitHub Actions; installed by sideloading.

Details, decisions and status: [docs/fork-notes.md](docs/fork-notes.md).

## Install

Download `voice-keyboard.apk` from the newest
[release](../../releases) and open it on the phone (allow installs from that app), or install over
wireless debugging with `tools/install-latest.sh`.

## Build

Needs JDK 21 and the Android SDK (platform 36, build-tools 36.0.0, NDK and CMake versions from
`gradle/tools.versions.toml`).

```bash
tools/fetch-sherpa-onnx.sh                 # on-device speech libraries (checksummed download)
tools/bergamot/build-android.sh arm64-v8a  # offline translation library
./gradlew :app:assembleDebug
```

Release signing reads an untracked `keystore.properties` (see `keystore.properties.template`); CI
writes it from repository secrets. Never commit keys.
