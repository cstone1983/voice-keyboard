#!/usr/bin/env bash
# Installs the newest Voice Keyboard release (pre-releases included) on a phone connected over adb
# (USB or wireless debugging). Run on a computer that can reach the phone.
#
#   tools/install-latest.sh [owner/repo] [adb-serial]
#
# Without owner/repo it uses this checkout's origin remote. Needs curl, python3 and adb.
set -euo pipefail

REPO="${1:-}"
SERIAL="${2:-}"
if [[ -z "$REPO" ]]; then
  url="$(git -C "$(dirname "$0")/.." remote get-url origin)"
  REPO="$(sed -E 's#.*github\.com[:/]([^/]+/[^/.]+)(\.git)?$#\1#' <<<"$url")"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=10" -o "$tmp/releases.json"
read -r TAG ASSET < <(python3 -I - "$tmp/releases.json" <<'PY'
import json, sys
for r in json.load(open(sys.argv[1])):
    if r.get("draft"):
        continue
    for a in r.get("assets", []):
        if a["name"] == "voice-keyboard.apk":
            print(r["tag_name"], a["browser_download_url"])
            sys.exit(0)
sys.exit("no release with voice-keyboard.apk found")
PY
)

echo "Installing $TAG"
curl -fsSL "$ASSET" -o "$tmp/voice-keyboard.apk"
adb_args=()
[[ -n "$SERIAL" ]] && adb_args=(-s "$SERIAL")
adb "${adb_args[@]}" install -r "$tmp/voice-keyboard.apk"
