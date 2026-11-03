#!/usr/bin/env bash
# Checks that every pin in backend/requirements-mobile.txt still resolves to
# wheels for each platform the mobile apps ship, installing nothing: the
# iPad's device and simulator (ios_13_0_arm64_iphoneos,
# ios_13_0_arm64_iphonesimulator) and Android's arm64 and x86_64 builds
# (android_24_arm64_v8a, android_24_x86_64), all for CPython 3.13, from PyPI
# and Flet's index. CI runs it (check.yml, mobile-pins), and so does
# bash ipad/scripts/build-python.sh --check.
#
#   bash tools/check_mobile_pins.sh
#
# (Through bash: the executable bit does not survive a commit from Windows.)
#
# Environment: GAMMA_PINS_PYTHON names the Python 3.13 pip runs under
# (default python3.13 when on PATH, else python3, else python). It must be a
# 3.13: pip evaluates environment markers for the Python running it, not for
# the target, whatever --python-version says. For the same reason a Windows
# Python fails the check: there mcp asks for pywin32, which no phone or
# tablet has a wheel of; Linux and macOS skip it, as the devices do.

set -euo pipefail

# The flags of the apps' own installs (ipad/scripts/build-python.sh, and
# Chaquopy's pip in android/app/build.gradle.kts): binary only, CPython 3.13
# (cp313 extensions, abi3 ones and pure "none" wheels all match), with
# Flet's index for the native packages PyPI builds for neither system.
PIP_FLAGS=(
  --only-binary=:all:
  --python-version 3.13
  --implementation cp
  --abi cp313
  --abi none
  --extra-index-url https://pypi.flet.dev
)
PLATFORMS=(
  ios_13_0_arm64_iphoneos
  ios_13_0_arm64_iphonesimulator
  android_24_arm64_v8a
  android_24_x86_64
)

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
requirements=$repo_dir/backend/requirements-mobile.txt
export PIP_DISABLE_PIP_VERSION_CHECK=1

say() { echo "check_mobile_pins: $*"; }
die() { echo "error: check_mobile_pins: $*" >&2; exit 1; }

python=${GAMMA_PINS_PYTHON:-$(command -v python3.13 || command -v python3 || command -v python || true)}
[ -n "$python" ] || die "no Python found: set GAMMA_PINS_PYTHON to a Python 3.13"
version=$("$python" -c 'import sys; print("%d.%d" % sys.version_info[:2])') ||
  die "$python does not run"
[ "$version" = 3.13 ] ||
  die "$python is Python $version; pip evaluates markers for the Python it runs under, so set GAMMA_PINS_PYTHON to a Python 3.13"
# (grep reads the whole help, so pipefail sees no broken pipe.)
"$python" -m pip install --help 2>/dev/null | grep -- --dry-run >/dev/null ||
  die "$python has no pip with install --dry-run (pip 22.2 or newer)"
case "$("$python" -c 'import sys; print(sys.platform)')" in
  win32|cygwin|msys)
    echo "warning: check_mobile_pins: a Windows Python evaluates mcp's pywin32 marker as true, and pywin32 has no mobile wheel; run this on Linux or macOS" >&2
    ;;
esac

# pip download has no dry run, so this is pip install --dry-run: it fetches
# into pip's cache and installs nothing. --target keeps the Python's own
# packages out of the resolution (and its PEP 668 marker out of the way).
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
for platform in "${PLATFORMS[@]}"; do
  say "resolving $requirements for $platform"
  "$python" -m pip install --dry-run --quiet --target "$scratch" \
    --platform "$platform" "${PIP_FLAGS[@]}" -r "$requirements"
done
say "every pin resolves for ${PLATFORMS[*]}"
