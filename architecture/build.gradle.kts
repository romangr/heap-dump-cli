// Build-time guard of the engine boundary (tests only).
plugins { java }

dependencies {
    testImplementation(project(":engine-api"))
    testImplementation(project(":engine-mat"))
    testImplementation(project(":hprof-stream"))
    testImplementation(project(":analysis"))
    testImplementation(project(":protocol"))
    testImplementation(project(":daemon"))
    testImplementation(project(":cli"))
    testImplementation(libs.archunit)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
