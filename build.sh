#!/usr/bin/env bash
# Builds WirePlug: Go engine tests, the gomobile AAR, then the APK.
#   ./build.sh            debug APK
#   ./build.sh release    signed release APK (needs ~/.config/wireplug/keystore.properties)
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
fi
export ANDROID_HOME ANDROID_NDK_HOME

variant=${1:-debug}

echo "== Go engine tests"
go vet ./...
go test -count=1 ./engine

echo "== gomobile bind"
# gomobile and gobind are pinned in go.mod as tools.
bin=$PWD/.build/bin
mkdir -p "$bin" app/libs
go build -o "$bin/" golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
PATH=$bin:$PATH gomobile bind -target=android -androidapi 26 \
    -javapkg org.metalmind.wireplug.go -trimpath -ldflags '-s -w' \
    -o app/libs/engine.aar ./engine

echo "== Gradle ($variant)"
task=assembleDebug
[[ $variant == release ]] && task=assembleRelease
./gradlew --no-daemon :app:testDebugUnitTest ":app:$task"

find app/build/outputs/apk -name '*.apk' -newer app/libs/engine.aar -print
