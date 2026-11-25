# Wi-Fi setup ("Find my server on Wi-Fi") — server side

The app can set itself up from the home network without a QR code. The server owner approves each
request (Telegram alert with Approve / Deny). Until a request is approved the app holds no key and
neither uses nor syncs anything; it shows "Waiting for approval".

## 1. Announce the server on the local network (mDNS / DNS-SD)

- Service type `_voicekb._tcp`, port of the gateway on the LAN (today 8080).
- TXT record: `path=/v1/` (where the API is), optional `name=<display name>`.
- The service name is shown in the app ("Voice server").

## 2. Pairing endpoints — home network only, no API key

Reachable only from the LAN (refuse anything that came through the public tunnel / internet, e.g.
check the client address is in the home subnets and the request did not come via the tunnel).
Rate-limit (e.g. 5 requests per minute per address, 3 open requests at a time).

- `GET /v1/vk/pair/info` → `{"name": "Voice server", "base_url": "https://<public>/v1/",
  "people": [{"user": "<id>", "name": "<display name>"}]}`
- `POST /v1/vk/pair` with `{"person": "<id>"}` (existing person) **or** `{"new_name": "<name>"}`
  (someone new), and `"device": "<device name>"` → `202 {"request_id": "<random, unguessable>",
  "code": "<4 digits>", "expires_in": 600}`. Sends the owner a Telegram message: who (person or "new
  person <name>"), device, code, the requesting LAN address, with **Approve** and **Deny** buttons.
- `GET /v1/vk/pair/<request_id>` → `{"status": "pending" | "approved" | "denied" | "expired"}`. When
  approved it also carries `"setup": { …the same object as the setup QR code… }` with a **new key for
  this device**, tied to that person (a personal key, not the household key; `user_id` = the person's
  id). The setup is handed out **once**; afterwards the request is gone (404 = expired for the app).
  Requests expire after 10 minutes unanswered.

On approval of a new person: add them to the people list first (id from the name, made unique).
Approve/deny happen only through the owner's Telegram (the bot must check it is the owner's chat).
`voice-user list` should show the new keys with their device names so they can be revoked.

## What the app does

`app/setup/LanPairing.kt` (discovery, the three calls, the waiting request kept across screens) and
`LanPairingDialog.kt` (search → choose person / someone new → ask → "Waiting for approval" with the
code → on approval the setup code is applied exactly like a scanned QR code). The app polls every 3 s
while the screen is open and keeps the request (no key, there is none yet) for up to a day.
