#!/bin/sh
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DIRNAME=$(dirname "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD="maximum"
warn () {
    echo "$*"
} >&2
die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2
CLASSPATH=$DIRNAME/gradle/wrapper/gradle-wrapper.jar
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
