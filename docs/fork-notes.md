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

## Server setup code (QR)

The server shows a QR code with one line of JSON:

```json
{"v":1,"base_url":"https://<server>/v1/","api_key":"<64 hex chars>"}
```

- Unknown fields are ignored; optional `stt_model` / `chat_model` prefill the model fields.
- Speech-to-text: `base_url + "audio/transcriptions"`; cleanup: `base_url + "chat/completions"`;
  `Authorization: Bearer <api_key>` on every request. `GET <base_url>../health` answers `ok` without a key
  and is used as the connection test.
- The app reads the code by photo (ZXing), from a saved image, or pasted from the clipboard. The key is
  stored encrypted (Keystore) and never logged.
- Models are settings, picked from `GET <base_url>models` or typed (they may change after tuning).

## Chunked dictation (low latency on a CPU-only server)

Upstream's long-form segmented mode already transcribes cut segments while recording, shows them as a
replaceable preview, sends the last 200 characters + custom words as the Whisper prompt, and runs
cleanup once on the joined text. Voice Keyboard changes:

- On by default (`AUTO`).
- New cut rule when Smart Turn is off (`SimpleCutPolicy`): a pause (default 0.5 s) ends a chunk once it
  has at least 3 s; a chunk is cut anyway at 15 s, at the quietest window of the last second. Cuts go into
  the silence, so no word is split. Settings: Dictation → Long-form → sliders.
- One upload at a time, strictly in order (the next chunk waits for the previous one, so its prompt
  already contains the previous text).
- Network loss: a chunk is retried up to 5 times over ~15 s (it waits on disk; later chunks wait in line).
- Timing: Settings → Dictation → Dictation timing (per chunk and stop-to-text, chunked and single), and
  `adb logcat -s VKTiming` (durations only, never text).

Possible server change that would help further (not done, server is managed separately): a streaming
endpoint (WebSocket, OpenAI realtime-style) would remove per-request overhead and let the last chunk start
before stop.

## Learning (Phase 2)

Server API under `<base_url>vk/` (same Bearer key; the server maps key -> person). Code in
`app/.../vk/learning/`.

- Capture (`VkLearning`): after a dictation is inserted in the keyboard, the span is followed by the
  ~30 characters before/after it on each selection change. Ends on Send/Go/Done, field cleared, field
  left, keyboard hidden, or the next dictation. Never in password fields, fields with
  `IME_FLAG_NO_PERSONALIZED_LEARNING`, or excluded apps. Only the span (and 3 words of context per edit)
  leaves the phone.
- Diff (`CorrectionDiff`): word level; skip when nothing changed or >40 % changed; text typed on at
  either end is not a correction. `POST vk/corrections`.
- "Learn my writing style": the final span on Send/clear -> `POST vk/messages` (category from the app).
- Uploads are queued (pref JSON), retried on the next keyboard start, dropped after 7 days.
- Profile: `GET vk/profile?app=` at most hourly per app; `whisper_prompt` goes into Whisper's prompt
  (with the previous-chunk context), `replacements` are applied whole-word before cleanup,
  `cleanup_style` + up to 3 examples + "fix only fillers/grammar/punctuation, keep wording and tone, add
  nothing" are added to the automatic Clean up prompt.
- Who's using the keyboard (household key): `VkIdentity` is the one place that decides it. Picked from
  `GET vk/users` (own-server form and Learning → Switch user), sent as `X-VK-As` on every `vk/` request
  (not on speech-to-text or cleanup). A 400 "select a user" shows the picker. Queued uploads keep the
  person they were captured for; profiles are cached per person and app. To be replaced by a real
  sign-in later.
- Settings → Dictation → Learning: both switches (default off), excluded apps, learned words with
  delete (`DELETE vk/learned/<id>`), Export (`GET vk/export`), Forget everything (`DELETE vk/data`).
  "Signed in as" from `GET vk/me` after saving a server.

## Split keyboard tuned from a typing test (2026-11-14)

Test: three pangram-style sentences, unfolded, two thumbs, autocorrect off. ~18 % characters wrong,
almost all systematic: on each half touches land pulled toward the half's centre (r→e, t→r, a→s, b→n,
p→o), the right thumb about a row low (o→k ×4, h→n ×3), space→"." ×5, stray spaces from taps read as
swipes. Changes:

- `SplitTouchCorrection`: letter touches spread 15 % outward from each half's centre, right half lifted
  30 % of a row; only letters/digits, never across the gap; space/enter/backspace/punctuation unchanged.
  Settings → Dictation → "Split typing correction" + two sliders.
- Halves narrower on a foldable (310 dp portrait, 330 dp landscape).
- Split bottom row: right space bar takes a third of the "." key.
- Swipe typing off while split (setting to turn it back on).
- Space bar shows the short language name ("English").

Next: repeat the test and adjust the two values.

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
- Own server first: setup's main card is "Your own server" (`app/setup/OwnServerSetup.kt`: QR scan,
  paste, address + key, health check, model pickers, save); the same form is in Settings → AI providers
  → "Connect your own server" (`DictateOwnServerScreen`). Uses `com.google.zxing:core` (Apache-2.0).

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
