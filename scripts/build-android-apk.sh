#!/usr/bin/env bash
# Собирает release APK модуля composeApp и кладёт копию в корень проекта new_vk/output/.
# Только APK; для APK + desktop Uber JAR см. scripts/build-dist.sh.
#
# Использование:
#   ./scripts/build-android-apk.sh              # release
#   ./scripts/build-android-apk.sh debug      # debug
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND="$ROOT/frontend"
VARIANT="${1:-release}"

case "$VARIANT" in
  release|rel|r)
    GRADLE_TASK=":composeApp:assembleRelease"
    APK_REL="composeApp/build/outputs/apk/release/composeApp-release.apk"
    OUT_NAME="vkturn-release.apk"
    ;;
  debug|dbg|d)
    GRADLE_TASK=":composeApp:assembleDebug"
    APK_REL="composeApp/build/outputs/apk/debug/composeApp-debug.apk"
    OUT_NAME="vkturn-debug.apk"
    ;;
  *)
    echo "Неизвестный вариант: $VARIANT (ожидалось: release | debug)" >&2
    exit 2
    ;;
esac

OUT_DIR="$ROOT/output"

mkdir -p "$OUT_DIR"
cd "$FRONTEND"

echo ">>> Gradle: $GRADLE_TASK"
./gradlew "$GRADLE_TASK" --no-daemon

APK_SRC="$FRONTEND/$APK_REL"
if [[ ! -f "$APK_SRC" ]]; then
  echo "APK не найден по ожидаемому пути: $APK_SRC" >&2
  exit 1
fi

DEST="$OUT_DIR/$OUT_NAME"
cp -f "$APK_SRC" "$DEST"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
SNAPSHOT="$OUT_DIR/${OUT_NAME%.apk}-${TS}.apk"
cp -f "$APK_SRC" "$SNAPSHOT"

echo ">>> Готово:"
echo "    $DEST"
echo "    $SNAPSHOT"
