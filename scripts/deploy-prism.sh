#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="/home/la/.local/share/PrismLauncher/instances/1.21.10 test/minecraft/mods"
JDK21_HOME="/home/la/.local/jdks/jdk-21.0.10+7"

if [[ ! -d "$TARGET_DIR" ]]; then
	echo "Target directory does not exist: $TARGET_DIR" >&2
	exit 1
fi

cd "$PROJECT_DIR"

if [[ -d "$JDK21_HOME" ]]; then
	export JAVA_HOME="$JDK21_HOME"
	export PATH="$JAVA_HOME/bin:$PATH"
fi

./gradlew build

MOD_JAR="$(find "$PROJECT_DIR/build/libs" -maxdepth 1 -type f -name '*.jar' \
	! -name '*-sources.jar' \
	! -name '*-dev.jar' \
	! -name '*-dev-shadow.jar' \
	| sort | tail -n 1)"

if [[ -z "$MOD_JAR" ]]; then
	echo "No remapped mod jar found in build/libs" >&2
	exit 1
fi

MOD_NAME="$(basename "$MOD_JAR")"

find "$TARGET_DIR" -maxdepth 1 -type f -name 'xclipsen-irc-bridge-*.jar' -delete
cp -f "$MOD_JAR" "$TARGET_DIR/$MOD_NAME"

echo "Deployed $MOD_NAME to $TARGET_DIR"
