@echo off

set JAVAFX=javafx-jmods-25.0.2

rmdir /s /q runtime
rmdir /s /q out

jlink --module-path "%JAVA_HOME%\jmods;%JAVAFX%" --add-modules java.base,java.desktop,java.logging,java.sql,jdk.unsupported,javafx.base,javafx.graphics,javafx.controls,javafx.fxml --output runtime

jpackage --name FBSBarcode --input target --main-jar FBSBarcode-1.0-SNAPSHOT.jar --main-class com.tuandev.fbsbarcode.Launcher --runtime-image runtime --dest out --app-version 1.0.0 --win-menu --win-shortcut --icon app.ico --type exe --java-options "--add-modules=javafx.controls,javafx.fxml" --java-options "--enable-native-access=ALL-UNNAMED" --java-options "--enable-native-access=javafx.graphics"

pause