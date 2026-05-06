#!/bin/sh

MAVEN_PROJECTBASEDIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading Maven wrapper..."
  curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar" -o "$WRAPPER_JAR"
  if [ $? -ne 0 ]; then
    echo "Failed to download maven-wrapper.jar"
    exit 1
  fi
fi

exec java "-Dmaven.multiModuleProjectDirectory=$MAVEN_PROJECTBASEDIR" -cp "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
