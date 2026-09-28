#!/usr/bin/env bash
# Provision a development machine for Parity: the backend virtualenv, JDK 17,
# and the Android SDK components the client needs.
#
# Written for a fresh, ephemeral container (a cloud dev environment reclones
# the repo each session, so nothing outside the working tree survives), but it
# works the same on a clean laptop checkout. Every stage is idempotent and
# skipped when its tooling is already present, so re-running is cheap.
#
#   scripts/bootstrap-dev.sh              # provision everything
#   scripts/bootstrap-dev.sh --backend    # backend virtualenv only
#   scripts/bootstrap-dev.sh --android    # JDK + Android SDK only
#   scripts/bootstrap-dev.sh --verify     # run the full test/lint gate
#
# Network egress is required for the stages that install anything. A sandboxed
# environment must allow: pypi.org, files.pythonhosted.org, deb.debian.org,
# security.debian.org, dl.google.com, maven.google.com, services.gradle.org,
# plugins.gradle.org, repo.maven.apache.org, repo1.maven.org, oss.sonatype.org
# (the last for Robolectric's android-all jars). The Android-side hosts are
# allowlisted under sandbox.network.allowedDomains in .claude/settings.json.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND="${REPO_ROOT}/backend"
ANDROID="${REPO_ROOT}/android"

# Matches .github/workflows/android.yml (java-version: 17) and the
# JavaVersion.VERSION_17 / jvmTarget = "17" in android/app/build.gradle.kts.
JDK_PACKAGE="openjdk-17-jdk-headless"

# Matches compileSdk / targetSdk in android/app/build.gradle.kts.
SDK_PLATFORM="platforms;android-36"
SDK_BUILD_TOOLS="build-tools;36.0.0"

# Honour an existing SDK location, then fall back to the Android Studio
# convention, then to a repo-local directory. The last case is what a
# sandboxed agent gets: there, $HOME is mounted read-only and only the working
# tree is writable. .android-sdk/ is gitignored.
if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  : # caller decided
elif [ -n "${ANDROID_HOME:-}" ]; then
  ANDROID_SDK_ROOT="$ANDROID_HOME"
elif [ -w "$HOME" ]; then
  ANDROID_SDK_ROOT="${HOME}/Android/Sdk"
else
  ANDROID_SDK_ROOT="${REPO_ROOT}/.android-sdk"
fi

# Bump this revision if the download 404s — Google publishes only the current
# one. The number is visible on https://developer.android.com/studio under
# "Command line tools only".
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"

log()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
skip() { printf '    (skip) %s\n' "$*"; }
die()  { printf '\nbootstrap-dev: %s\n' "$*" >&2; exit 1; }

# apt needs root. Prefer running as root, fall back to non-interactive sudo,
# and fail with an actionable message rather than hanging on a password prompt.
#
# An agent sandbox typically sets the no_new_privileges flag, which defeats
# sudo outright — so the JDK stage cannot run from inside a session and belongs
# in the container image or the environment's setup script instead.
as_root() {
  if [ "$(id -u)" = 0 ]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    sudo "$@"
  else
    die "need root for: $*
  Run this script as root during image build or environment setup. It cannot
  install system packages from inside a sandboxed agent session."
  fi
}

bootstrap_backend() {
  log "Backend virtualenv (${BACKEND}/.venv)"

  # pip warns noisily on every install when the default cache is unwritable,
  # which is the norm under a sandbox that mounts $HOME read-only.
  if ! mkdir -p "${XDG_CACHE_HOME:-${HOME}/.cache}/pip" 2>/dev/null; then
    export PIP_CACHE_DIR="${TMPDIR:-/tmp}/parity-pip-cache"
  fi

  if [ ! -x "${BACKEND}/.venv/bin/python" ]; then
    python3 -m venv "${BACKEND}/.venv" \
      || die "python3 -m venv failed (is python3-venv installed?)"
  else
    skip "virtualenv already exists"
  fi

  # The editable install builds through setuptools>=68 in an isolated env, so
  # pip must be able to reach PyPI even when the venv ships a usable pip.
  "${BACKEND}/.venv/bin/pip" install --quiet --upgrade pip setuptools wheel \
    || die "pip could not reach PyPI (allow pypi.org and files.pythonhosted.org)"
  ( cd "$BACKEND" && .venv/bin/pip install --quiet -e ".[dev]" ) \
    || die "editable install of backend failed"

  printf '    python %s, flask %s\n' \
    "$("${BACKEND}/.venv/bin/python" -V | cut -d' ' -f2)" \
    "$("${BACKEND}/.venv/bin/python" -c 'from importlib.metadata import version; print(version("flask"))')"
}

bootstrap_jdk() {
  log "JDK 17"

  if command -v javac >/dev/null 2>&1; then
    skip "javac present: $(javac -version 2>&1)"
    return
  fi

  as_root apt-get update -qq
  as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$JDK_PACKAGE" \
    || die "could not install ${JDK_PACKAGE} (check apt sources and egress)"
  printf '    %s\n' "$(javac -version 2>&1)"
}

bootstrap_android_sdk() {
  log "Android SDK (${ANDROID_SDK_ROOT})"

  local sdkmanager="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"

  if [ ! -x "$sdkmanager" ]; then
    local tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN

    command -v unzip >/dev/null 2>&1 \
      || as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq unzip

    curl -fsSL -o "${tmp}/${CMDLINE_TOOLS_ZIP}" "$CMDLINE_TOOLS_URL" \
      || die "download failed: ${CMDLINE_TOOLS_URL} (bump CMDLINE_TOOLS_ZIP, or allow dl.google.com)"
    unzip -q "${tmp}/${CMDLINE_TOOLS_ZIP}" -d "$tmp"

    # sdkmanager resolves the SDK root by walking up from its own location, so
    # it only works from cmdline-tools/latest/.
    mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"
    rm -rf "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
    mv "${tmp}/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
  else
    skip "cmdline-tools already installed"
  fi

  yes 2>/dev/null | "$sdkmanager" --licenses >/dev/null || true
  "$sdkmanager" --install "platform-tools" "$SDK_PLATFORM" "$SDK_BUILD_TOOLS" >/dev/null \
    || die "sdkmanager install failed (allow dl.google.com)"

  # Gradle finds the SDK via local.properties; it is gitignored, so writing it
  # here is safe. Leave an existing file alone in case it points elsewhere.
  if [ ! -f "${ANDROID}/local.properties" ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "${ANDROID}/local.properties"
    printf '    wrote android/local.properties\n'
  else
    skip "android/local.properties already exists"
  fi
}

verify() {
  log "Backend gate (pytest, ruff check, ruff format)"
  [ -x "${BACKEND}/.venv/bin/pytest" ] \
    || die "backend virtualenv missing — run without --verify first"
  (
    cd "$BACKEND"
    # pyproject sets filterwarnings = ["error"], so a warning fails the suite.
    SECRET_KEY=test .venv/bin/pytest -q
    .venv/bin/ruff check .
    .venv/bin/ruff format --check .
  )

  log "Android gate (unit tests)"
  local android_ran=yes
  if command -v javac >/dev/null 2>&1 && [ -f "${ANDROID}/local.properties" ]; then
    ( cd "$ANDROID" && ./gradlew --no-daemon testDebugUnitTest )
  else
    skip "JDK or android/local.properties missing"
    android_ran=no
  fi

  if [ "$android_ran" = yes ]; then
    log "All gates passed"
  else
    # Don't claim a clean bill of health for a gate that never ran.
    log "Backend gate passed; Android gate SKIPPED (toolchain absent)"
  fi
}

main() {
  case "${1:-all}" in
    all)
      bootstrap_backend
      bootstrap_jdk
      bootstrap_android_sdk
      log "Done — run 'scripts/bootstrap-dev.sh --verify' to exercise the toolchain"
      ;;
    --backend) bootstrap_backend ;;
    --android) bootstrap_jdk; bootstrap_android_sdk ;;
    --verify)  verify ;;
    # Print the header comment block, stopping at the first non-comment line so
    # this stays correct when the header is edited.
    -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' \
                 "${BASH_SOURCE[0]}" ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
}

main "$@"
