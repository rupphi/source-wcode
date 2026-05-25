#!/bin/bash
set -e

PACKAGE_TYPE="${1:-app-image}"

if [[ ! "$PACKAGE_TYPE" =~ ^(app-image|exe|msi|deb|rpm|dmg|pkg)$ ]]; then
    echo "Invalid package type: $PACKAGE_TYPE"
    echo "Usage: ./build.sh [app-image|exe|msi|deb|rpm|dmg|pkg]"
    exit 1
fi

APP_NAME="WCode"
VENDOR=$(./mvnw help:evaluate -Dexpression=app.vendor -q -DforceStdout 2>/dev/null)
if [ -z "$VENDOR" ]; then
    echo "Could not read app.vendor from pom.xml, using default"
    VENDOR="TuanDev"
fi

# Read version from pom.xml using Maven
APP_VERSION=$(./mvnw help:evaluate -Dexpression=app.version -q -DforceStdout 2>/dev/null)
if [ -z "$APP_VERSION" ]; then
    echo "Could not read app.version from pom.xml, using default"
    APP_VERSION="1.0.0"
fi
echo "Building version: $APP_VERSION"

MAIN_JAR="FBSBarcode-${APP_VERSION}.jar"
MAIN_CLASS="com.tuandev.fbsbarcode.Launcher"

echo "Packaging with Maven..."
./mvnw -q clean package

if [ ! -f "target/${MAIN_JAR}" ]; then
    echo "Missing main jar: target/${MAIN_JAR}"
    exit 1
fi

rm -rf out target/jpackage-input
mkdir -p target/jpackage-input/lib
cp "target/${MAIN_JAR}" "target/jpackage-input/${MAIN_JAR}"
cp -R target/lib/. target/jpackage-input/lib/

# Platform-specific options
INSTALLER_OPTS=""
case "$PACKAGE_TYPE" in
    exe|msi)
        INSTALLER_OPTS="--win-menu --win-shortcut --win-per-user-install"
        ;;
esac

echo "Building $PACKAGE_TYPE package..."
jpackage --type "$PACKAGE_TYPE" \
    --name "$APP_NAME" \
    --input target/jpackage-input \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --dest out \
    --app-version "$APP_VERSION" \
    --vendor "$VENDOR" \
    --icon src/main/resources/com/tuandev/fbsbarcode/assets/images/logo.ico \
    $INSTALLER_OPTS \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --java-options "-Djavafx.cachedir=/var/tmp/WCode/openjfx-cache" \
    --java-options "-Djava.io.tmpdir=/var/tmp/WCode/tmp" \
    --java-options "-Dorg.sqlite.tmpdir=/var/tmp/WCode/tmp" \
    --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services"

echo "Done. Output is in the out folder."
ls -la out/
