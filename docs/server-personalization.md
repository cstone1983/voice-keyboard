# Server side of keyboard personalization

What the keyboard app expects from the user's own server for per-person personalization. All requests use
the same Bearer key as transcription and name the person in `X-VK-As` (as the other `vk/*` endpoints).

## 1. `GET vk/personal` / `POST vk/personal` (new)

One keyboard profile per person, so it follows them to any device.

- `GET vk/personal` → `200` with the stored profile, or `200 {}` when the person has none yet.
  `404` is read by the app as "this server does not have the endpoint" (it retries a day later).
- `POST vk/personal` with the whole profile as JSON → `200 {"ok": true, "updated_at": <unix seconds>}`.
  The server stores it as the person's current profile (replace). The app has already merged the
  server's copy into its own before posting, so the server does not need to merge.
- Body up to about 4 MB; reject larger with `413`. (A full profile is typically well under 1 MB, but can
  approach 2 MB with the largest word-pair and fix lists.)
- **Several devices per person.** When storing, set the profile's `updated_at` to the server's time (so
  `GET` returns it). A `POST` may carry `"if_updated_at": <updated_at from the GET it merged>`: if the
  stored profile's `updated_at` is different (another device saved in between), do not store and answer
  `409 {"error": "changed", "updated_at": <stored>}`; the app reads again, merges and retries. When the
  GET returned no stored profile (empty or seeded), the app sends what it saw (0 or the seed's time):
  refuse with 409 if a stored profile exists by now. Without the field, store as before.
  Do not store `if_updated_at` itself.

Profile (format version 1; unknown fields must be kept or ignored, never rejected):

```json
{
  "version": 1,
  "updated_at": 1800000000,
  "words":      { "tomorrow": [12.5, 1800000000] },
  "learned":    [["Proxmox", "en", 6, 1800000000]],
  "pairs":      [["see", "you", "en", 14, 1800000000]],
  "dictionary": [["Proxmox", "en", null]],
  "touch":      { "s:v": [0.21, -0.05, 60.0], "n:v": [0.08, 0.0, 34.0] },
  "settings":   { "autocorrect": true, "autocorrect_strength": "AGGRESSIVE", "split_touch_spread_percent": 15 }
}
```

- `words`: word (lowercase) → [use score, last use]. Scores fade with a 30-day half-life on the device.
- `learned`: [word, language, sightings, last use], words the keyboard learned that are not in its dictionary.
- `pairs`: [previous word (folded), word, language, count, last use], for next-word prediction.
- `dictionary`: [word, locale or null, shortcut or null], the personal dictionary.
- `touch`: "s:" split / "n:" normal keyboard + letter → [x offset, y offset (key widths), samples].
- `settings`: typing settings by name.
- `fixes`: [typo, fix, count, last use], words the person changed by hand ("thw" → "the",
  "thisbis" → "this is"); used automatically from 2 sightings.
- `blocked`: [typed, wrong correction, time], auto-corrections the person took back.
- `confusion`: {"b>v": count}, keys hit for another (typed > meant; a space is " ").

This is everything the person types (outside incognito and password fields): treat it like messages.
Store per person, never log the body, include it in `vk/export`, and delete it with `DELETE vk/data`.

Optional: when a person has no profile yet, `GET` may return a seeded one, with `words` counted from their
stored dictations and messages (same format, lowercase words, score = number of uses, last use = time of
the newest one). The app merges it like any other copy.

## 2. `GET vk/me`: the person's own speech-to-text model

Add `"stt_model": "<model name>"` once the person's fine-tuned model can be served by
`/v1/audio/transcriptions`; leave it out (or `""`) before that. The app then sends that name as `model`
for that person's dictations (the person can turn this off). The transcription endpoint must not fail
if that model cannot be loaded: fall back to the default model.

## 3. Transcription prompt

The app now puts up to 20 of the person's own words (names, terms) at the start of the `prompt` field,
about 180 characters at most, e.g. `Tyler, Proxmox.`, before the style prompt and the previous text. No
change needed, but the server must not drop or rewrite the prompt.
