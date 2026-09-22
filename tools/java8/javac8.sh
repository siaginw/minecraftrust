#!/usr/bin/env bash
set -eo pipefail

JAVA8_CANDIDATE="${JAVA8_HOME:-/c/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot}"

if [ ! -x "${JAVA8_CANDIDATE}/bin/javac" ] && [ ! -x "${JAVA8_CANDIDATE}/bin/javac.exe" ]; then
    echo "[Hermes Error] javac 8 not found at ${JAVA8_CANDIDATE}. Set JAVA8_HOME." >&2
    exit 1
fi

exec "${JAVA8_CANDIDATE}/bin/javac" "$@"
