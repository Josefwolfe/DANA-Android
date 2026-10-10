#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    if command -v python3 >/dev/null 2>&1; then
        python3 "$APP_HOME/scripts/fetch_wrapper.py"
    else
        echo 'Python 3 is required for the initial verified Gradle wrapper download.' >&2
        exit 2
    fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
