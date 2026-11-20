// Long-running analysis daemon: sessions, jobs, Unix-socket server. Finds engines via ServiceLoader.
plugins { `java-library` }

dependencies {
    api(project(":protocol"))
    api(project(":analysis"))
    implementation(project(":hprof-stream"))
    runtimeOnly(project(":engine-mat"))

    testImplementation(project(":engine-tck"))
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    dependsOn(":fixtures:generateFixtures")
    systemProperty("hd.fixtures.dir", project(":fixtures").layout.buildDirectory.dir("dumps").get().asFile.absolutePath)
    maxHeapSize = "1g"
}
