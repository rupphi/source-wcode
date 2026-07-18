plugins {
    id("dev.jdesk.application") version "0.1.3"
    application
}

group = "com.tuandev"
version = "1.1.7"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val jdeskVersion = "0.1.3"
val junitVersion = "5.12.1"
val jdeskAutomationRuntime = configurations.create("jdeskAutomationRuntime")
val platform = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
    else -> "linux"
}

dependencies {
    implementation("dev.jdesk:jdesk-api:$jdeskVersion")
    implementation("dev.jdesk:jdesk-runtime:$jdeskVersion")
    runtimeOnly("dev.jdesk:jdesk-platform-$platform:$jdeskVersion")
    jdeskAutomationRuntime("dev.jdesk:jdesk-automation:$jdeskVersion")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.19.0")

    implementation("com.itextpdf:barcodes:8.0.3")
    implementation("com.itextpdf:bouncy-castle-connector:8.0.3")
    implementation("com.itextpdf:commons:8.0.3")
    implementation("com.itextpdf:font-asian:8.0.3")
    implementation("com.itextpdf:forms:8.0.3")
    implementation("com.itextpdf:io:8.0.3")
    implementation("com.itextpdf:kernel:8.0.3")
    implementation("com.itextpdf:layout:8.0.3")
    implementation("com.itextpdf:svg:8.0.3")
    implementation("org.apache.pdfbox:pdfbox:3.0.6")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.zxing:javase:3.5.4")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
}

sourceSets {
    named("main") {
        java {
            setSrcDirs(listOf("src/main/java", "src/jdesk/java"))
            exclude(
                "com/tuandev/fbsbarcode/Launcher.java",
                "com/tuandev/fbsbarcode/MainApplication.java",
                "com/tuandev/fbsbarcode/features/print/PrintOptionsDialogService.java",
                "com/tuandev/fbsbarcode/features/print/PrintTemplateDesignerService.java",
                "com/tuandev/fbsbarcode/features/shop/ShopDialogService.java",
                "com/tuandev/fbsbarcode/features/shop/ShopWorkflow.java",
                "com/tuandev/fbsbarcode/integration/update/UpdateDialogService.java",
                "com/tuandev/fbsbarcode/shared/AlertService.java",
                "com/tuandev/fbsbarcode/shared/AppTaskExecutor.java",
                "com/tuandev/fbsbarcode/shared/FxmlViewLoader.java",
                "com/tuandev/fbsbarcode/shared/ThemeService.java",
                "com/tuandev/fbsbarcode/ui/controls/CategoryFilterMenu.java",
                "com/tuandev/fbsbarcode/ui/dashboard/DashboardController.java",
                "com/tuandev/fbsbarcode/ui/fbo/FboPackingController.java",
                "com/tuandev/fbsbarcode/ui/fbo/FboProductRow.java",
                "com/tuandev/fbsbarcode/ui/history/PrintHistoryController.java",
                "com/tuandev/fbsbarcode/ui/kizmapping/KizGtinMappingEditor.java",
                "com/tuandev/fbsbarcode/ui/kizmapping/KizMappingController.java",
                "com/tuandev/fbsbarcode/ui/license/LicenseDialogService.java",
                "com/tuandev/fbsbarcode/ui/packing/PackingController.java",
                "com/tuandev/fbsbarcode/ui/print/PrintTemplateDesignerController.java",
                "com/tuandev/fbsbarcode/ui/report/ErrorReportDialog.java",
                "com/tuandev/fbsbarcode/ui/shop/ShopDialogController.java",
                "com/tuandev/fbsbarcode/ui/shop/ShopSidebarController.java",
                "com/tuandev/fbsbarcode/ui/supply/SupplyDetailController.java",
                "com/tuandev/fbsbarcode/ui/supply/SupplyListController.java",
                "com/tuandev/fbsbarcode/ui/supply/SupplyManagementController.java",
                "com/tuandev/fbsbarcode/ui/workspace/HomeController.java",
                "com/tuandev/fbsbarcode/ui/workspace/WorkspaceHeaderController.java",
                "com/tuandev/fbsbarcode/ui/znack/ZnackAutomationController.java",
            )
        }
        resources {
            setSrcDirs(listOf("src/main/resources", "src/jdesk/resources"))
            exclude("**/*.fxml", "css/**")
        }
    }
    named("test") {
        java.setSrcDirs(listOf("src/jdeskTest/java"))
        resources.setSrcDirs(listOf("src/jdeskTest/resources"))
    }
}

jdesk {
    applicationId.set("com.tuandev.wcode")
    mainClass.set("com.tuandev.fbsbarcode.jdesk.WCodeDesktop")
    frontend {
        directory.set(layout.projectDirectory.dir("ui"))
        devCommand.set(listOf("npm", "run", "dev"))
        buildCommand.set(listOf("npm", "run", "build"))
        devUrl.set("http://127.0.0.1:5173")
        distDirectory.set(layout.projectDirectory.dir("ui/dist"))
    }
}

application {
    mainClass.set("com.tuandev.fbsbarcode.jdesk.WCodeDesktop")
}

tasks.processResources {
    filesMatching("app.properties") {
        filter { line -> line.replace("\${app.version}", project.version.toString()) }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    dependsOn("jdeskFrontendBuild")
    classpath += jdeskAutomationRuntime
    doNotTrackState("launches a desktop application")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        jvmArgs("-XstartOnFirstThread")
    }
    systemProperty(
        "jdesk.assets.dir",
        layout.projectDirectory.dir("ui/dist").asFile.absolutePath,
    )
    System.getProperty("wcode.appdata.dir")?.let { appDataDir ->
        systemProperty("wcode.appdata.dir", appDataDir)
    }
    if (System.getProperty("jdesk.automation")?.toBoolean() == true) {
        systemProperty("jdesk.automation", "true")
    }
    System.getProperty("jdesk.automation.dir")?.let { automationDir ->
        systemProperty("jdesk.automation.dir", automationDir)
    }
}

tasks.register<JavaExec>("wcodeRecovery") {
    group = "application"
    description = "Runs the offline recovery CLI; pass --args='list|verify <id>|restore <id> --confirm'."
    mainClass.set("com.tuandev.fbsbarcode.jdesk.recovery.WCodeRecoveryCli")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    System.getProperty("wcode.appdata.dir")?.let { appDataDir ->
        systemProperty("wcode.appdata.dir", appDataDir)
    }
}

tasks.register("dev") {
    group = "jdesk"
    description = "Alias for jdeskDev."
    dependsOn("jdeskDev")
}

tasks.register("doctor") {
    group = "jdesk"
    description = "Alias for jdeskDoctor."
    dependsOn("jdeskDoctor")
}

tasks.register("bindings") {
    group = "jdesk"
    description = "Alias for jdeskGenerateBindings."
    dependsOn("jdeskGenerateBindings")
}

tasks.register("pkg") {
    group = "jdesk"
    description = "Alias for jdeskPackage."
    dependsOn("jdeskPackage")
}
