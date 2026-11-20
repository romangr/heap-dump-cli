// Shared by daemon and CLI: file locations, JSON envelope, Unix-socket transport.
plugins { `java-library` }

dependencies {
    api(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
