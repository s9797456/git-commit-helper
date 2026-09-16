#!/usr/bin/env bash
# Public status of the Commit Helper listing on JetBrains Marketplace.
#
#   scripts/listing-status.sh
#
# Tells you whether a version is actually installable yet: the listing page exists as soon as
# the plugin is created, but IDEs only see it once a version has been approved.
set -euo pipefail

ID=34303
XML_ID=com.caye.commithelper
PAGE="https://plugins.jetbrains.com/plugin/$ID-commit-helper"

echo "== listing =="
curl -fsS --max-time 20 "https://plugins.jetbrains.com/api/plugins/$ID" | python3 -c '
import json, sys
d = json.load(sys.stdin)
v = d.get("vendor", {})
print("name:       ", d.get("name"))
print("xmlId:      ", d.get("xmlId"), " (expected: com.caye.commithelper)")
print("vendor:     ", v.get("name"), "-", v.get("link"))
print("downloads:  ", d.get("downloads"), "| rating:", d.get("rating"), "from", d.get("ratingVotes"), "votes")
print("page:       ", "https://plugins.jetbrains.com" + d.get("link", ""))
'

echo
echo "== published versions (what IDEs can install right now) =="
feed=$(curl -fsS --max-time 20 "https://plugins.jetbrains.com/plugins/list?pluginId=$XML_ID")
if printf '%s' "$feed" | grep -q '<version>'; then
    printf '%s' "$feed" | grep -oE '<version>[^<]+</version>' | sed 's/^/  /'
    echo
    echo "A version is live: the shields.io version badge can be re-added to the READMEs as"
    echo "  https://img.shields.io/jetbrains/plugin/v/$ID"
else
    echo "  none yet - the first upload is still pending approval (or was never submitted)."
    echo "  Until then the version badge renders 'invalid response data' (that is why the"
    echo "  READMEs use a static Marketplace badge), and downloads stay at 0."
fi

echo
echo "listing page: $PAGE"
