#!/usr/bin/env bash
# The Gamma Connector's zip, as a release attaches it and the Chrome Web
# Store takes it: extension/ without its store listing (STORE.md, README.md,
# store/), this scripts/ folder and Finder's .DS_Store files, with the given
# version in the zipped manifest. Chrome's `version` takes only numbers, so a
# pre-release suffix (1.2.0-rc1) goes to `version_name`; the checkout's
# manifest stays untouched. desktop.yml zips every release with it and
# check.yml every pull request. Needs zip and jq.
#
#   bash extension/scripts/zip.sh <version> <out.zip>
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <version> <out.zip>" >&2
  exit 2
fi
version=$1
case $2 in
  /*) out=$2 ;;
  *) out=$PWD/$2 ;;
esac
extension=$(cd "$(dirname "$0")/.." && pwd)

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -R "$extension/." "$stage/"
rm -rf "$stage/STORE.md" "$stage/README.md" "$stage/store" "$stage/scripts"
find "$stage" -name .DS_Store -delete
jq --arg v "${version%%-*}" --arg n "$version" \
  '.version = $v | if $n != $v then .version_name = $n else . end' \
  "$extension/manifest.json" > "$stage/manifest.json"

rm -f "$out"
(cd "$stage" && zip -qr "$out" .)
echo "$out: manifest version $(unzip -p "$out" manifest.json | jq -r '.version + (if .version_name then " (" + .version_name + ")" else "" end)')"
