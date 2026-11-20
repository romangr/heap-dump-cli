// Generates small .hprof fixtures with known object graphs (see hd.fixtures.FixtureMain).
// Other modules depend on the `generateFixtures` task and read the dumps from fixtures/build/dumps.

val scenarios = listOf("graph", "leaks")
val dumpsDir = layout.buildDirectory.dir("dumps")

val generateFixtures by tasks.registering {
    group = "fixtures"
    description = "Generates all fixture heap dumps"
}

scenarios.forEach { scenario ->
    val task = tasks.register<JavaExec>("generateFixture${scenario.replaceFirstChar { it.uppercase() }}") {
        group = "fixtures"
        description = "Generates the '$scenario' fixture heap dump"
        mainClass.set("hd.fixtures.FixtureMain")
        classpath = sourceSets["main"].runtimeClasspath
        jvmArgs("-Xmx256m", "-XX:+UseSerialGC")
        val out = dumpsDir.map { it.file("$scenario.hprof") }
        args(scenario, out.get().asFile.absolutePath)
        inputs.files(sourceSets["main"].runtimeClasspath)
        outputs.file(out)
    }
    generateFixtures { dependsOn(task) }
}

// Manual: ./gradlew :fixtures:generateLargeFixture -Pout=/path/large.hprof [-Pentries=15000000]
tasks.register<JavaExec>("generateLargeFixture") {
    group = "fixtures"
    description = "Generates a large dump for calibration (not used by tests)"
    mainClass.set("hd.fixtures.FixtureMain")
    classpath = sourceSets["main"].runtimeClasspath
    val entries = (project.findProperty("entries") ?: "15000000").toString()
    jvmArgs("-Xmx6g", "-XX:+UseParallelGC", "-Dhd.fixture.entries=$entries")
    args("large", (project.findProperty("out") ?: layout.buildDirectory.file("large/large.hprof").get().asFile.absolutePath).toString())
}
