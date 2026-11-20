// Engine contract suite (TCK). An engine module extends EngineContract in its tests.
plugins { `java-library` }

dependencies {
    api(project(":engine-api"))
    api(platform(libs.junit.bom))
    api("org.junit.jupiter:junit-jupiter")
}
