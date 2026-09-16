#!/usr/bin/env bash
# Public status of the Commit Helper listing on JetBrains Marketplace.
#
#   scripts/listing-status.sh
#
# Answers the two questions that matter while a release is in flight:
#   1. has the version been submitted, and is it approved yet?
#   2. can IDEs install it right now (the public update feed IDEs actually use)?
set -euo pipefail

ID=34303
XML_ID=com.caye.commithelper
PAGE="https://plugins.jetbrains.com/plugin/$ID-commit-helper"

echo "== listing =="
curl -fsS --max-time 20 "https://plugins.jetbrains.com/api/plugins/$ID" | python3 -c '
import json, sys
d = json.load(sys.stdin)
v = d.get("vendor", {})
approved = bool(d.get("approve"))
pending = bool(d.get("hasUnapprovedUpdate"))

print("name:       ", d.get("name"))
print("xmlId:      ", d.get("xmlId"), " (expected: com.caye.commithelper)")
print("vendor:     ", v.get("name"), "-", v.get("link"))
print("contact:    ", d.get("email"))
print("pricing:    ", d.get("pricingModel"), "| hidden:", d.get("isHidden"), "| blocked:", d.get("isBlocked"))
print("downloads:  ", d.get("downloads"), "| rating:", d.get("rating"), "from", d.get("ratingVotes"), "votes")
print("screens:    ", len(d.get("screens") or []))
u = d.get("urls") or {}
print("licenseUrl: ", u.get("licenseUrl") or "(empty)")
print("sourceCode: ", u.get("sourceCodeUrl") or "(empty - required for open-source licenses)")
print("tags:       ", ", ".join(t["name"] for t in (d.get("tags") or [])) or "(none)")

if approved:
    print("approval:    APPROVED - live on the Marketplace")
elif pending:
    print("approval:    PENDING REVIEW - submitted, waiting for JetBrains moderation")
else:
    print("approval:    NOT SUBMITTED - no version is awaiting review")
'

echo
echo "== public update feed (what IDEs can install right now) =="
feed=$(curl -fsS --max-time 20 "https://plugins.jetbrains.com/plugins/list?pluginId=$XML_ID")
if printf '%s' "$feed" | grep -q '<version>'; then
    printf '%s' "$feed" | grep -oE '<version>[^<]+</version>' | sed 's/^/  /'
    echo
    echo "A version is live. Optional next step: swap the static Marketplace badge in the"
    echo "READMEs for the dynamic one, https://img.shields.io/jetbrains/plugin/v/$ID"
else
    echo "  empty - nothing installable yet (expected while the first version is in review)."
    echo "  The version badge would render 'invalid response data', which is why the READMEs"
    echo "  use a static Marketplace badge for now."
fi

echo
echo "listing page: $PAGE"
