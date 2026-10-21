#!/usr/bin/env bash
# Builds a signed release and publishes it on GitHub; installed apps pick it up as an update.
# Usage: scripts/release.sh "Release notes, one change per line"
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^val appVersionName = "\(.*\)"/\1/p' app/build.gradle.kts)
notes=${1:-"Kick TV $version"}
tag="v$version"

if git rev-parse "$tag" >/dev/null 2>&1; then
  echo "Tag $tag already exists. Bump appVersionName/appVersionCode in app/build.gradle.kts first." >&2
  exit 1
fi
[ -f keystore.properties ] || { echo "keystore.properties missing: releases must be signed with the release key." >&2; exit 1; }

./gradlew lintRelease dist
apk="dist/KickTV-$version.apk"
cp "$apk" dist/KickTV.apk   # fixed name -> stable .../releases/latest/download/KickTV.apk link

git tag -a "$tag" -m "Kick TV $version"
git push origin "$tag"
gh release create "$tag" "$apk" dist/KickTV.apk --title "Kick TV $version" --notes "$notes"
echo "Published $tag"
