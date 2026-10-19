#!/usr/bin/env bash
# Compila el APK "Mi-Cerebro" de depuración (o release con: tools/compilar.sh release)
set -euo pipefail
source "$(dirname "$0")/env.sh"; cd "$RAIZ"
SDK_WIN="$(cd "$ANDROID_HOME" && pwd -W)"
# local.properties exige "C\:/ruta/con/barras"
echo "sdk.dir=${SDK_WIN/:/\:}" > local.properties
TAREA=":androidApp:assembleDebug"; [ "${1:-}" = release ] && TAREA=":androidApp:assembleRelease"
./gradlew --console=plain "$TAREA"
find androidApp/build/outputs/apk -name 'Mi-Cerebro*.apk'
