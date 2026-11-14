# Voice Keyboard: fork notes, decisions and status

App-side plan and status. The server side (speech-to-text, cleanup model, gateway, API key, firewall)
is handled separately; this repo only needs its base URL and an API key, entered in the app.

## Decisions

| Date | Decision | Why |
|---|---|---|
| 2026-11-14 | Fork **Dictate** (Apache-2.0), not FlorisBoard or HeliBoard | Dictate already is a full FlorisBoard keyboard (swipe, suggestions, autocorrect) with OpenAI-compatible transcription + rewording, prompts, hold/toggle, custom words. Apache-2.0 allows this. HeliBoard is GPL-3.0 with no dictation; plain FlorisBoard has no dictation. |
| 2026-11-14 | Keep upstream git history, add `upstream` remote | Upstream fixes can be merged later; changes kept small and marked `Voice Keyboard:` in comments. |
| 2026-11-14 | Look and feel modelled on the keyboard the user uses today (flat keys, tinted function keys, accent enter key, Material You colours, swipe on, no symbol hints) | User's request. No third-party names, logos or artwork are used. |
| 2026-11-14 | Remove Google Play Billing; hide Dictate Cloud | Not distributed through Play; the app talks only to the user's server. Billing is a stub with upstream's API so merges stay small. |
| 2026-11-14 | Encrypt API keys with the Android Keystore (AES-256-GCM) | Upstream stores them as plain text in settings. Keys copied to another device (backup/restore) cannot be decrypted there and must be entered again. |
| 2026-11-14 | arm64-v8a only | One target phone; smaller APK. `-PallAbis=true` restores upstream's set. |
| 2026-11-14 | New application id `io.github.cstone1983.voicekeyboard`, name "Voice Keyboard" | Installs next to Dictate if both are present; no personal data in the id. |
| 2026-11-14 | Build and sign in GitHub Actions; signing key kept as repo secrets (key created on a home machine, root-only) | Key never in git or chat. Every CI build gets a growing versionCode so it installs over the previous one. |
| 2026-11-14 | Install by sideloading: release download, or `tools/install-latest.sh` over wireless debugging from a computer on the phone's network | The cloud build session cannot reach the phone directly. |

## Changes made (vs. upstream)

- `app/build.gradle.kts`: application id; arm64-only by default; billing dependency removed.
- `AndroidManifest.xml`: `com.android.vending.BILLING` permission removed.
- `dictate/cloud/DictateCloudBilling.kt`: stub, always "billing unavailable".
- `ProviderRegistry.presets`, `ProviderListing.pinnedIds`, setup screen, "What's new" tour, settings
  search: Dictate Cloud removed from what the user sees.
- `dictate/provider/SecretBox.kt` + `ProviderAccounts.Serializer`: keys encrypted at rest;
  `ProviderAccount.toString()` never prints the key.
- Themes `classic_you_day/night` (Material You, default) and `classic_day/night` (fixed colours),
  generated from the FlorisBoard day stylesheet.
- Defaults: swipe typing on, symbol hints off, Classic themes.
- `.github/workflows/build.yml`: build, sign, publish releases. Upstream's funding, issue templates and
  monthly-maintenance workflow removed.
- `tools/install-latest.sh`: install the newest release over adb.

## Status

Phase 1 (MVP keyboard) in progress:

- [x] Fork imported with history, rebranded, attribution in NOTICE/README
- [x] Billing and Dictate Cloud removed from the build and the UI
- [x] API key encryption
- [x] Classic themes and defaults
- [x] CI workflow; signing secrets set; first signed pre-release `build-1000002` (2026-11-14),
      certificate `CN=Voice Keyboard`, SHA-256 `DB:F6:6A:11:...:D9:4F`
- [ ] First signed build installed on the phone and checked
- [ ] Settings check against the plan: server URL + key, models, language, hold-to-talk / toggle,
      silence auto-stop, auto-send, cleanup on/off, cleanup presets ("clean up", "formal", "casual",
      "translate") — most exist upstream; fill gaps after testing on the phone
- [ ] Test against the user's server once it is up

Later phases (from the plan): learning from corrections (opt-in, on the user's server), first-run
training session, streaming and voice commands.

## Merging upstream

```bash
git fetch upstream main
git merge upstream/main
```

Conflicts are most likely in the files listed above. Re-run `tools/fetch-sherpa-onnx.sh` and the
Bergamot build if their versions change.
