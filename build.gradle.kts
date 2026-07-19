import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    id("dev.jdesk.application") version "0.1.3"
    application
}

group = "com.tuandev"
version = providers.gradleProperty("appVersion").orElse("1.1.7").get()

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
    testImplementation("dev.jdesk:jdesk-webview-spi:$jdeskVersion")
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
    applicationName.set("WCode")
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
    val updateManifestPublicKey = providers.gradleProperty("updateManifestPublicKey")
        .orElse(providers.environmentVariable("UPDATE_MANIFEST_PUBLIC_KEY"))
        .orElse("")
    val updateSigningPublisher = providers.gradleProperty("updateSigningPublisher")
        .orElse(providers.environmentVariable("UPDATE_SIGNING_PUBLISHER"))
        .orElse("")
    filesMatching("app.properties") {
        filter { line ->
            line.replace("\${app.version}", project.version.toString())
                .replace("\${app.update.public-key}", updateManifestPublicKey.get())
                .replace("\${app.update.publisher}", updateSigningPublisher.get())
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// jDesk 0.1.3 passes every runtime JAR to jdeps as both a classpath entry and a root. Named
// multi-release dependencies such as sqlite-jdbc and POI then cannot resolve their automatic-module
// dependencies because the plugin supplies only --class-path. Build one classes-only analysis JAR
// without module descriptors so jdeps can discover the complete JDK-module set in the unnamed
// module. The authoritative package task still copies every original runtime JAR unchanged.
val jdeskJdepsAnalysisJar = tasks.register<Jar>("jdeskJdepsAnalysisJar") {
    archiveClassifier.set("jdeps-analysis")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.filter { it.extension == "jar" }.map { zipTree(it) }
    })
    include("**/*.class")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}

tasks.named<dev.jdesk.gradle.tasks.JDeskRuntimeImageTask>("jdeskRuntimeImage") {
    appClasspath.setFrom(jdeskJdepsAnalysisJar.flatMap { it.archiveFile })
}

tasks.named<dev.jdesk.gradle.tasks.JDeskPackageTask>("jdeskPackage") {
    appVersion.set(project.version.toString())
    description = "Compatibility entry point for the authoritative WCode package task."
    dependsOn("wcodePackage")
    enabled = false
}

val wcodePackageInput = tasks.register<Sync>("wcodePackageInput") {
    dependsOn(tasks.named("jar"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("jdesk/package-input"))
}

val wcodePackageImage = tasks.register<Exec>("wcodePackageImage") {
    group = "jdesk"
    description = "Builds the WCode app-image with the offline recovery launcher."
    dependsOn(wcodePackageInput, tasks.named("jdeskRuntimeImage"))
    val packageRoot = layout.buildDirectory.dir("jdesk/package")
    val packageInput = layout.buildDirectory.dir("jdesk/package-input")
    val runtimeImage = layout.buildDirectory.dir("jdesk/runtime-image")
    val recoveryLauncher = layout.projectDirectory.file("packaging/WCode-Recovery.properties")
    inputs.dir(packageInput).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(runtimeImage).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(recoveryLauncher)
    outputs.dir(packageRoot)
    doFirst {
        delete(packageRoot)
        val javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().metadata.installationPath
        val executable = javaHome.file(
            "bin/jpackage" + if (platform == "windows") ".exe" else "",
        ).asFile.absolutePath
        val arguments = mutableListOf(
            executable,
            "--type", "app-image",
            "--name", "WCode",
            "--input", packageInput.get().asFile.absolutePath,
            "--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(),
            "--main-class", "com.tuandev.fbsbarcode.jdesk.WCodeDesktop",
            "--runtime-image", runtimeImage.get().asFile.absolutePath,
            "--dest", packageRoot.get().asFile.absolutePath,
            "--app-version", project.version.toString(),
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--java-options", "-Dwcode.production=true",
            "--java-options", "-Djdesk.assets.classpath=web",
            "--java-options", "-Djdesk.applicationName=WCode",
            "--add-launcher", "WCode-Recovery=${recoveryLauncher.asFile.absolutePath}",
        )
        if (platform == "macos") {
            arguments.addAll(listOf(
                "--java-options", "-XstartOnFirstThread",
                "--mac-package-identifier", "com.tuandev.wcode",
            ))
        }
        commandLine(arguments)
    }
}

val wcodePackageEvidence = tasks.register("wcodePackageEvidence") {
    dependsOn(wcodePackageImage)
    val packageRoot = layout.buildDirectory.dir("jdesk/package")
    inputs.dir(packageRoot)
    outputs.files(
        packageRoot.map { it.file("checksums.sha256") },
        packageRoot.map { it.file("sbom.cyclonedx.json") },
        packageRoot.map { it.file("sbom.spdx.json") },
    )
    doLast {
        val root = packageRoot.get().asFile.toPath()
        listOf("checksums.sha256", "sbom.cyclonedx.json", "sbom.spdx.json")
            .forEach { Files.deleteIfExists(root.resolve(it)) }
        val checksums = dev.jdesk.packager.ReleaseArtifacts.writeChecksums(
            root,
            root.resolve("checksums.sha256"),
        )
        val artifacts = buildList {
            add(tasks.named<Jar>("jar").get().archiveFile.get().asFile.toPath())
            configurations.runtimeClasspath.get().files
                .filter { it.isFile }
                .mapTo(this) { it.toPath() }
        }.distinct()
        val components = dev.jdesk.packager.ReleaseArtifacts.inspectJars(artifacts)
        dev.jdesk.packager.ReleaseArtifacts.writeSbom(
            root.resolve("sbom.cyclonedx.json"),
            "com.tuandev.wcode",
            project.version.toString(),
            checksums,
            components,
        )
        val sourceDateEpoch = System.getenv("SOURCE_DATE_EPOCH")
        val createdAt = if (sourceDateEpoch != null && sourceDateEpoch.matches(Regex("\\d+"))) {
            Instant.ofEpochSecond(sourceDateEpoch.toLong())
        } else {
            Instant.now()
        }.truncatedTo(ChronoUnit.SECONDS).toString()
        dev.jdesk.packager.ReleaseArtifacts.writeSpdxSbom(
            root.resolve("sbom.spdx.json"),
            "com.tuandev.wcode",
            project.version.toString(),
            checksums,
            components,
            createdAt,
        )
    }
}

val wcodePackageVerify = tasks.register("wcodePackageVerify") {
    dependsOn(wcodePackageEvidence)
    val packageRoot = layout.buildDirectory.dir("jdesk/package")
    inputs.dir(packageRoot)
    doLast {
        val root = packageRoot.get().asFile.toPath()
        val recoveryRelative = when (platform) {
            "macos" -> "WCode.app/Contents/MacOS/WCode-Recovery"
            "windows" -> "WCode/WCode-Recovery.exe"
            else -> "WCode/bin/WCode-Recovery"
        }
        val recovery = root.resolve(recoveryRelative)
        if (Files.isSymbolicLink(recovery) || !Files.isRegularFile(recovery)) {
            throw GradleException("The packaged offline recovery launcher is missing or unsafe.")
        }
        val sourceJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile.toPath()
        val packagedMainJars = Files.walk(root).use { files ->
            files.filter { it.fileName.toString() == sourceJar.fileName.toString() }
                .filter { !Files.isSymbolicLink(it) && Files.isRegularFile(it) }
                .toList()
        }
        val packagedMainJar = packagedMainJars.singleOrNull()
        if (packagedMainJar == null || Files.mismatch(sourceJar, packagedMainJar) != -1L) {
            throw GradleException("The packaged main JAR is stale or missing.")
        }
        val launcherConfigs = Files.walk(root).use { files ->
            files.filter { it.fileName.toString() == "WCode.cfg" }
                .filter { !Files.isSymbolicLink(it) && Files.isRegularFile(it) }
                .toList()
        }
        val launcherConfig = launcherConfigs.singleOrNull()?.let(Files::readString)
        if (launcherConfig?.contains("java-options=-Dwcode.production=true") != true) {
            throw GradleException("The main launcher does not enforce bundled production content.")
        }
        for (evidence in listOf("checksums.sha256", "sbom.cyclonedx.json", "sbom.spdx.json")) {
            val evidenceFile = root.resolve(evidence)
            if (!Files.isRegularFile(evidenceFile)
                    || !Files.readString(evidenceFile).contains(recoveryRelative)) {
                throw GradleException("$evidence does not describe the offline recovery launcher.")
            }
        }
    }
}

tasks.register("wcodePackage") {
    group = "jdesk"
    description = "Builds WCode, its offline recovery launcher, checksums, and SBOMs."
    dependsOn(wcodePackageVerify)
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
    description = "Alias for the authoritative WCode package with offline recovery evidence."
    dependsOn("wcodePackage")
}
