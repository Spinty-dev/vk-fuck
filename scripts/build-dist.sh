#!/usr/bin/env bash
# Общая сборка артефактов frontend: Android APK и desktop Uber JAR (текущая ОС/арх —
# см. задачи package*UberJarForCurrentOS от Compose Gradle Plugin).
#
# Использование:
#   ./scripts/build-dist.sh              # release APK + vkturn-*-*-release.jar
#   ./scripts/build-dist.sh debug       # debug APK + vkturn-*-*.jar (без суффикса -release)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND="$ROOT/frontend"
OUT="$ROOT/output"
VARIANT="${1:-release}"

case "$VARIANT" in
  release|rel|r)
    GRADLE_TASKS=( ":composeApp:assembleRelease" ":composeApp:packageReleaseUberJarForCurrentOS" )
    APK_REL="composeApp/build/outputs/apk/release/composeApp-release.apk"
    OUT_APK="vkturn-release.apk"
    JAR_KIND="release"
    ;;
  debug|dbg|d)
    GRADLE_TASKS=( ":composeApp:assembleDebug" ":composeApp:packageUberJarForCurrentOS" )
    APK_REL="composeApp/build/outputs/apk/debug/composeApp-debug.apk"
    OUT_APK="vkturn-debug.apk"
    JAR_KIND="dev"
    ;;
  *)
    echo "Неизвестный вариант: $VARIANT (ожидалось: release | debug)" >&2
    exit 2
    ;;
esac

mkdir -p "$OUT"

build_desktop_natives_for_uber_jar() {
  local s="$FRONTEND/scripts/build-native.sh"
  if [[ ! -f "$s" ]]; then
    echo ">>> Нет $s — uber-JAR без встроенных бинарников (или положите в composeApp/native/<os-arch>/)." >&2
    return 0
  fi
  echo ">>> Нативки desktop в composeApp/native/<хост>/ (sing-box + vk-turn client) для uber-JAR:"
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64|Linux/amd64)
      bash "$s" desktop-linux
      ;;
    Darwin/arm64|Darwin/aarch64)
      bash "$s" desktop-macos-arm64
      ;;
    Darwin/x86_64|Darwin/i386)
      bash "$s" desktop-macos-x64
      ;;
    *)
      echo "    Пропуск: для $(uname -s)/$(uname -m) нет desktop-target в scripts/build-native.sh — JAR может быть без бинарников." >&2
      ;;
  esac
}

build_desktop_natives_for_uber_jar

cd "$FRONTEND"

echo ">>> Gradle (один запуск): ${GRADLE_TASKS[*]}"
./gradlew "${GRADLE_TASKS[@]}" --no-daemon

APK_SRC="$FRONTEND/$APK_REL"
if [[ ! -f "$APK_SRC" ]]; then
  echo "APK не найден: $APK_SRC" >&2
  exit 1
fi

JAR_DIR="$FRONTEND/composeApp/build/compose/jars"
select_jar() {
  local -a cand=()
  shopt -s nullglob
  if [[ "$JAR_KIND" == release ]]; then
    cand=( "$JAR_DIR"/vkturn-*-release.jar )
  else
    for f in "$JAR_DIR"/vkturn-*.jar; do
      [[ "$f" == *"-release.jar" ]] && continue
      [[ "$f" == *"-sources.jar" ]] && continue
      cand+=( "$f" )
    done
  fi
  if ((${#cand[@]} == 0)); then
    echo "В $JAR_DIR не найден ожидаемый uber-jar (kind=$JAR_KIND)." >&2
    ls -la "$JAR_DIR" 2>/dev/null || true
    return 1
  fi
  local best="${cand[0]}"
  local f
  for f in "${cand[@]}"; do
    [[ "$f" -nt "$best" ]] && best="$f"
  done
  printf '%s\n' "$best"
}

JAR_SRC="$(select_jar)"
if [[ ! -f "$JAR_SRC" ]]; then
  exit 1
fi

TS="$(date -u +%Y%m%dT%H%M%SZ)"

DEST_APK="$OUT/$OUT_APK"
cp -f "$APK_SRC" "$DEST_APK"
SNAPSHOT_APK="$OUT/${OUT_APK%.apk}-${TS}.apk"
cp -f "$APK_SRC" "$SNAPSHOT_APK"

JAR_BASENAME="$(basename "$JAR_SRC")"
DEST_JAR="$OUT/$JAR_BASENAME"
cp -f "$JAR_SRC" "$DEST_JAR"
SNAPSHOT_JAR="$OUT/${JAR_BASENAME%.jar}-${TS}.jar"
cp -f "$JAR_SRC" "$SNAPSHOT_JAR"

echo ">>> Готово:"
echo "    $DEST_APK"
echo "    $SNAPSHOT_APK"
echo "    $DEST_JAR  (desktop: java -jar ...)"
echo "    $SNAPSHOT_JAR"
