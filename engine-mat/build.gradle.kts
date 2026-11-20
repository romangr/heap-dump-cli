// MAT-backed engine. The ONLY module allowed to depend on org.eclipse.mat.* (enforced by ArchitectureTest).
plugins { `java-library` }

dependencies {
    api(project(":engine-api"))
    implementation(libs.bundles.mat)

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
