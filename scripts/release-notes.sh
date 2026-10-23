#!/usr/bin/env bash
# Prints the GitHub release notes for a version: its CHANGELOG.md section plus install instructions.
# Usage: scripts/release-notes.sh 1.3.1
set -euo pipefail
cd "$(dirname "$0")/.."

version=${1:?usage: release-notes.sh <version>}
repo="DIRIKtv/sidekick"

# The lines between "## <version>" and the next "## " heading.
section=$(awk -v v="$version" '
  /^## / { if (found) exit; if ($2 == v) { found = 1; next } }
  found { print }
' CHANGELOG.md | sed -e '/./,$!d')   # drop leading blank lines
[ -n "$section" ] || { echo "No \"## $version\" section in CHANGELOG.md" >&2; exit 1; }

# The previous release, for the "all changes" link.
prev=$( (git tag -l 'v*'; echo "v$version") | sort -uV | grep -B1 -x "v$version" | grep -vx "v$version" || true)

cat <<EOF
$section

---

### Install or update

**Already have Sidekick?** It offers this update the next time you open it, or go to **Settings → Check for updates**.

**New to Sidekick?** Download **Sidekick.apk** below. On Fire TV, open the Downloader app and enter:

\`\`\`
https://github.com/$repo/releases/latest/download/Sidekick.apk
\`\`\`

Step-by-step setup for Fire TV, Google TV and Android TV is in the [README](https://github.com/$repo#install).
EOF

if [ -n "$prev" ]; then
  echo
  echo "**All changes:** [$prev...v$version](https://github.com/$repo/compare/$prev...v$version)"
fi
