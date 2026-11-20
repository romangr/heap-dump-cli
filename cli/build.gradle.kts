// The `hd` command: thin picocli client that talks to (and starts) the analysis daemon.
plugins { application }

dependencies {
    implementation(project(":protocol"))
    implementation(libs.picocli)
    // the daemon is started from the same installation (same classpath, different main class)
    runtimeOnly(project(":daemon"))

    testImplementation(project(":engine-tck"))
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "hd"
    mainClass.set("hd.cli.Main")
    // the client only parses arguments and talks to the daemon: small heap, fast startup
    applicationDefaultJvmArgs = listOf("-Xmx64m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xshare:auto")
}

tasks.named<CreateStartScripts>("startScripts") {
    optsEnvironmentVar = "HD_CLIENT_OPTS"
}

tasks.test {
    dependsOn(":fixtures:generateFixtures", "installDist")
    systemProperty("hd.fixtures.dir", project(":fixtures").layout.buildDirectory.dir("dumps").get().asFile.absolutePath)
    systemProperty("hd.install.dir", layout.buildDirectory.dir("install/hd").get().asFile.absolutePath)
}

distributions {
    main {
        distributionBaseName.set("hd")
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("NOTICE"))
            from(rootProject.file("THIRD-PARTY-NOTICES.md"))
            from(rootProject.file("plugins/heap-dump/skills/heap-dump")) { into("skill") }
        }
    }
}

// the skill ships inside cli.jar so `hd skill install` always installs the version matching the binary
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("plugins/heap-dump/skills/heap-dump/SKILL.md")) { into("hd/skill") }
}

// archives unpack into a stable top-level "hd/" folder; the version is in the file name only
tasks.withType<AbstractArchiveTask>().matching { it.name == "distZip" || it.name == "distTar" }.configureEach {
    eachFile { path = path.replaceFirst(Regex("^hd-[^/]+/"), "hd/") }
    includeEmptyDirs = false
}
tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
