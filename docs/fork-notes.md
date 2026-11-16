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

## Cleanup guards (server finding 2026-11-14)

The cleanup model improvises on empty/near-empty input ("text", or 60 s rambles). `VkCleanup`:
- transcription under 3 characters: no cleanup request;
- cleanup requests send `temperature: 0.2` and `max_tokens` ≈ input chars / 2 + 50;
- output rejected (raw text inserted) when empty, "text", containing `<text`, or < 30 % / > 300 % of
  the input length; such a dictation is not used for learning;
- spoken punctuation ("comma", "period" at a sentence end, "full stop", "question mark",
  "exclamation mark", "new line", "new paragraph") converted before cleanup (English).

## Learning from how the user speaks (2026-11-14)

- Dictation record `POST vk/dictations` after every dictation (with "Learn from my corrections"): id,
  app, category, lang, stt_model, raw Whisper text, cleaned (inserted), final (same end point as the
  corrections; "" when the span could not be followed), audio_ms, low_confidence, timings. Queued.
- low_confidence: `response_format=verbose_json` + `timestamp_granularities[]=word` on the own server
  when learning is on; words with probability < 0.6 (empty when the server sends no probabilities).
- "Help it learn my voice" (off): the whole recording (chunks merged) `POST vk/audio?dictation=<id>`
  after the record, Wi-Fi only by default, max 20 MB, kept in `files/vk_clips` until sent (7 days).
  "Delete my voice clips" = `DELETE vk/audio` + local clips.
- Profile `?app=&category=`; cleanup gets "Match this writing style …" + `cleanup_style` and "Do not add
  these words unless spoken: …" from `cleanup_avoid`.
- Learning screen: progress from `vk/stats` (accuracy per day as bars, totals, voice minutes, "Voice
  training: ready / X of 120 minutes"), "avoid" entries deletable, learned styles read-only.

## Speed fix after the learning build (2026-11-14)

Server measured ~9 s stop-to-text (was ~5 s): word timestamps doubled Whisper time on the CPU-only
server, and three style examples in every cleanup prompt made cleanup 2-3x slower. Now: no word
timestamps (`low_confidence: []`), cleanup prompt carries only `cleanup_style` and `cleanup_avoid`;
temperature 0.2 and `max_tokens` (≈ 2× input tokens + 50) confirmed on the wire by a test.

## Cleanup safety net (2026-11-14, cleanup added a sentence from the style notes)

- The automatic Clean up is sent as two messages: system = cleanup instruction + "Style notes (tone
  only - never add, remove or change words because of these): <cleanup_style>" + avoid list + the "be
  precise" prompt; user = only the framed text. No style examples.
- `VkCleanup.rejectReason`: empty / "text" / `<text>`, more words than raw + 2, more sentences than
  raw, a sentence > 60 % new words, shorter than half the raw text → raw text (after learned
  replacements) inserted, logged as `VKCleanup: rejected reason=…`, no corrections for that dictation.
- Trade-off: "more sentences" also rejects a cleanup that only splits an unpunctuated run-on sentence.

## Voice training (2026-11-14)

Settings → Voice Keyboard → Learning → Voice training (`VkTrainingScreen`, `vk/training/VkTraining`):
- Reading rounds of 10 sentences (generic script: messages, numbers/dates/times, punctuation, home and
  tech; 3 per round built from the user's learned words from `vk/learned`) and free-talk prompts.
- Each recording is transcribed by the user's server (no cleanup) and shown against the script with
  missed words struck through and an accuracy score; free talk is corrected by the user.
- "Send" uploads `POST vk/dictations` (`final` = script or corrected text, `source: "training"`) and the
  audio to `vk/audio` (always for training; Wi-Fi-only setting respected). Progress bar from `vk/stats`
  toward 120 minutes, "ready" when the server says `fine_tune_ready`.

## Server review fixes (2026-11-15)

1. Chunk echo: the continuity text goes LAST in the Whisper prompt (Whisper keeps the end); a chunk whose
   words are > 60 % already at the end of the previous text or in the prompt is dropped
   (`VkChunkGuard`, log `phase=chunkEchoDropped`).
2. Chunk defaults (new v2 keys so installed phones change too): at least 8 s before a pause may cut,
   0.6 s pause, 15 s max.
3. Chunk retries: 3 attempts, only for network/timeout/5xx; 20 s per request (25 s whole call); a lost
   chunk shows a "Part of your dictation could not be transcribed" toast.
4. Learning queue: record and audio are queued in one locked write; audio waits while its record is
   anywhere in the queue, and a 404 is retried later instead of deleting the clip. A voice clip always
   gets its record, even with "Learn from my corrections" off.
5. Timings/audio_ms are attached to the dictation when measured (`VkLearning.attachTimings`), not read
   at send time.
6. Only 400 "select a user", 401/403/408/429 and 5xx are retried; other 4xx are dropped. Training sends
   raw = final when the transcription is empty.
7. Automatic cleanup: 8 s limit (client and coroutine), then the raw text is inserted.
8. No `vk/profile` calls and no learned profile applied when both learning switches are off.
9. Setup QR: `user_id` is the identity (`*` = household key, ask via `vk/users`); `user` is display only.
10. Spoken punctuation: only marks it inserted get a space/capital ("voice.example.com", "e.g.",
    "Node.js" untouched).
11. Learning queue and profile cache moved from the settings datastore (backed up / device transfer) to
    `files/vk_learning` (not in the backup rules); the old settings copies are cleared once. http://
    servers only on the home network (private IPs, .lan/.local/.home.arpa/.internal, bare names);
    cleartext stays enabled in the manifest for those. SecretBox fails closed (key not stored if the
    Keystore fails). `/v1/models` `task: automatic-speech-recognition` marks speech models.

## Suggestions favour the person's most-used words (2026-11-16)

- `ime/nlp/latin/PersonalWordCounts.kt`: counts every finished word (typed, picked from the strip,
  auto-corrected to, dictated) per selected person, in `files/vk_learning/words_<hash>.json` (device only,
  outside backup; the file name is a hash, not the person's id). 30-day half-life; at most ~6000 words.
- Not counted in incognito/private fields or where suggestions are off.
- Ranking: a word used 3+ times earns a strip rank of 196 + 12·ln(uses), 209–250. It is lifted only when
  that beats its dictionary rank, and only for words the dictionary or personal dictionary knows (a slip
  typed often never becomes a suggestion). At most 3 lifted per strip; never auto-committed by this.
- Switch: Voice Keyboard → Typing & autocorrect → "Suggest my most-used words first" (on).
  "Forget everything" also clears the person's counts.
- Possible later: a server `GET vk/words` to seed counts from past dictations/messages (server chat).

## Per-person personalization (2026-11-16)

Everything the keyboard learns on the phone now belongs to the selected person, and can follow them
to other devices through their own server. Server contract: `docs/server-personalization.md`.

1. **Per-person files** (`vk/learning/VkPersonFiles.kt`): learned words and word pairs
   (`dictate_learned_words`), the personal dictionary (`floris_user_dictionary`), word counts and touch
   habits each have one file per person (hashed id), filled from that person's server profile. Nobody
   inherits what is on the phone: the old files are used only while nobody is selected, and are added to
   a person only with "Add it to <name>'s profile" on the Learning page (offered once).
   Choosing a person downloads their whole profile at once, settings included, then their spelling hint
   and voice model. An already selected person gets it on the first keyboard start after the update.
2. **Spelling hint for speech-to-text** (`VkVocabularyHint.kt`): up to 20 of the person's own words (learned
   words + personal dictionary, most used first, ≤180 chars) go at the start of the transcription prompt.
   Built in the background when recording starts. Switch: "Spell my names and words".
3. **Next-word predictions from dictation**: dictated text feeds the same learned word pairs as typing
   (`NlpManager.learnWordPairsFromText`, within clauses, numbers break a pair).
4. **Touch habits per key** (`ime/nlp/latin/TouchHabits.kt`): mean landing offset per letter, split and
   normal keyboard apart, from finished words (as typed, or the autocorrected word when the lengths match).
   Used after 8 samples, capped at 0.35/0.30 key widths, last ~60 samples. A touch goes to the key whose
   learned aim point is nearest. Switch: "Learn my touch habits".
5. **Profile sync** (`VkPersonSync.kt`): `GET`/`POST vk/personal` every 6 h at keyboard start, right after a
   person switch, or with "Sync my profile now". Merge keeps the larger value (never adds). Settings come
   from the server on a person's first sync on a device, then go up from the device. 404 = no endpoint
   (retry after a day).
6. **Own voice model**: `vk/me` `stt_model` is stored per person and used as the transcription model on
   the own server. Switch: "Use my own voice model".

Also: a spoken "comma" next to a comma that is already there no longer gives ",," (`VkCleanup`).
"Forget everything" also clears the person's counts, touch habits and sync state on the phone.

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
