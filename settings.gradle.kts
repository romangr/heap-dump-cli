rootProject.name = "heap-dump-cli"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // MAT core plugins, installed by scripts/install-mat.sh (not on Maven Central)
        mavenLocal {
            content { includeGroup("org.eclipse.mat") }
        }
    }
}

include("fixtures", "engine-api", "engine-mat", "engine-tck", "hprof-stream", "analysis", "protocol", "daemon", "cli", "architecture")
