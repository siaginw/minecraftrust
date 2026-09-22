#!/usr/bin/env bash
set -eo pipefail

JAVA8_HOME_PATH="${JAVA8_HOME:-/c/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot}"

if [ ! -d "${JAVA8_HOME_PATH}" ]; then
    echo "[RustCraft Error] Java 8 home not found at ${JAVA8_HOME_PATH}. Set JAVA8_HOME." >&2
    exit 1
fi

export JAVA_HOME="${JAVA8_HOME_PATH}"
export PATH="${JAVA8_HOME_PATH}/bin:${PATH}"

exec "$@"
