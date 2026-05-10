#!/bin/bash
set -e

PACKAGE_TYPE="${1:-app-image}"

if [[ ! "$PACKAGE_TYPE" =~ ^(app-image|exe|msi|deb|rpm|dmg|pkg)$ ]]; then
    echo "Invalid package type: $PACKAGE_TYPE"
    echo "Usage: ./build.sh [app-image|exe|msi|deb|rpm|dmg|pkg]"
    exit 1
fi

APP_NAME="FBSBarcode"

# Read version from pom.xml using Maven
APP_VERSION=$(./mvnw help:evaluate -Dexpression=app.version -q -DforceStdout 2>/dev/null)
if [ -z "$APP_VERSION" ]; then
    echo "Could not read app.version from pom.xml, using default"
    APP_VERSION="1.0.0"
fi
echo "Building version: $APP_VERSION"

MAIN_JAR="FBSBarcode-${APP_VERSION}.jar"
MAIN_CLASS="com.tuandev.fbsbarcode.Launcher"
VENDOR="TuanDev"

echo "Packaging with Maven..."
./mvnw -q package

if [ ! -f "target/${MAIN_JAR}" ]; then
    echo "Missing main jar: target/${MAIN_JAR}"
    exit 1
fi

rm -rf out

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
    --input target \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --dest out \
    --app-version "$APP_VERSION" \
    --vendor "$VENDOR" \
    --icon src/main/resources/com/tuandev/fbsbarcode/logo-fbs.ico \
    $INSTALLER_OPTS \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --java-options "--enable-native-access=javafx.graphics" \
    --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services"

echo "Done. Output is in the out folder."
ls -la out/
