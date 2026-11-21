#!/usr/bin/env bash
# Installs the newest Voice Keyboard build (pre-releases included) from cstone1983/app-builder on a phone
# connected over adb (USB or wireless debugging). Normally Obtainium does this on the phone itself; this
# is for a phone without it. Run on a computer that can reach the phone.
#
#   tools/install-latest.sh [owner/repo] [adb-serial]
#
# Picks the newest release whose title starts with "voice-keyboard " and its .apk asset.
# Needs curl, python3 and adb.
set -euo pipefail

REPO="${1:-cstone1983/app-builder}"
SERIAL="${2:-}"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=30" -o "$tmp/releases.json"
read -r TAG ASSET < <(python3 -I - "$tmp/releases.json" <<'PY'
import json, sys
for r in json.load(open(sys.argv[1])):
    if r.get("draft") or not (r.get("name") or "").startswith("voice-keyboard "):
        continue
    for a in r.get("assets", []):
        if a["name"].endswith(".apk"):
            print(r["tag_name"], a["browser_download_url"])
            sys.exit(0)
sys.exit("no voice-keyboard release with an .apk found")
PY
)

echo "Installing $TAG"
curl -fsSL "$ASSET" -o "$tmp/voice-keyboard.apk"
adb_args=()
[[ -n "$SERIAL" ]] && adb_args=(-s "$SERIAL")
adb "${adb_args[@]}" install -r "$tmp/voice-keyboard.apk"
