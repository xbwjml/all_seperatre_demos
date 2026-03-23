#!/bin/sh
# Maven Wrapper script
# This script downloads and runs the Maven Wrapper JAR if not already present

set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
MAVEN_WRAPPER_PROPERTIES_FILE="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$MAVEN_WRAPPER_JAR" ]; then
    if [ -f "$MAVEN_WRAPPER_PROPERTIES_FILE" ]; then
        WRAPPER_URL=$(grep 'wrapperUrl' "$MAVEN_WRAPPER_PROPERTIES_FILE" | cut -d'=' -f2-)
        echo "Downloading Maven Wrapper from $WRAPPER_URL"
        if command -v wget > /dev/null; then
            wget -q -O "$MAVEN_WRAPPER_JAR" "$WRAPPER_URL"
        elif command -v curl > /dev/null; then
            curl -sL -o "$MAVEN_WRAPPER_JAR" "$WRAPPER_URL"
        else
            echo "Error: Neither wget nor curl found." >&2
            exit 1
        fi
    fi
fi

DISTRIBUTION_URL=$(grep 'distributionUrl' "$MAVEN_WRAPPER_PROPERTIES_FILE" | cut -d'=' -f2-)
MAVEN_HOME="$HOME/.m2/wrapper/dists/$(basename "$DISTRIBUTION_URL" .zip)"

if [ ! -d "$MAVEN_HOME" ]; then
    mkdir -p "$MAVEN_HOME"
    TMPFILE=$(mktemp)
    echo "Downloading Maven from $DISTRIBUTION_URL"
    if command -v wget > /dev/null; then
        wget -q -O "$TMPFILE" "$DISTRIBUTION_URL"
    elif command -v curl > /dev/null; then
        curl -sL -o "$TMPFILE" "$DISTRIBUTION_URL"
    fi
    unzip -q -d "$MAVEN_HOME" "$TMPFILE"
    rm -f "$TMPFILE"
fi

MVN_CMD=$(find "$MAVEN_HOME" -name mvn -type f -path "*/bin/mvn" | head -1)
exec "$MVN_CMD" "$@"
