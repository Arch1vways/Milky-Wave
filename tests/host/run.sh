#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 -d "$OUT" $(find "$ROOT/tests/host/stubs" -name '*.java') "$ROOT/app/src/main/java/dev/milkywave/MaterialYou.java" "$ROOT/app/src/main/java/dev/milkywave/Config.java" "$ROOT/tests/host/dev/milkywave/ColorTest.java"
java -cp "$OUT" dev.milkywave.ColorTest
