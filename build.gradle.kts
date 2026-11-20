allprojects {
    group = "hd"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-Xlint:all,-serial,-processing")
    }

    tasks.withType<Jar>().configureEach {
        manifest { attributes("Implementation-Title" to project.name, "Implementation-Version" to project.version) }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
