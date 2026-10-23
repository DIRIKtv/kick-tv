#!/usr/bin/env bash
# Builds a signed release and publishes it on GitHub; installed apps pick it up as an update.
# Usage: add a "## <version>" section to CHANGELOG.md, then run scripts/release.sh
# (the release notes are built from that section by scripts/release-notes.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^val appVersionName = "\(.*\)"/\1/p' app/build.gradle.kts)
tag="v$version"

if git rev-parse "$tag" >/dev/null 2>&1; then
  echo "Tag $tag already exists. Bump appVersionName/appVersionCode in app/build.gradle.kts first." >&2
  exit 1
fi
[ -f keystore.properties ] || { echo "keystore.properties missing: releases must be signed with the release key." >&2; exit 1; }
notes=$(scripts/release-notes.sh "$version")   # fails early if CHANGELOG.md has no section for it

./gradlew lintRelease dist
apk="dist/Sidekick-$version.apk"
cp "$apk" dist/Sidekick.apk   # fixed name -> stable .../releases/latest/download/Sidekick.apk link

git tag -a "$tag" -m "Sidekick $version"
git push origin "$tag"
gh release create "$tag" "$apk" dist/Sidekick.apk --title "Sidekick $version" --notes "$notes"
echo "Published $tag"
