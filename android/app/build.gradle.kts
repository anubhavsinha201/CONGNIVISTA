plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arogyax.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arogyax.app"
        // 26, not 24: the data layer is built on java.time (OffsetDateTime,
        // DateTimeFormatter, ChronoUnit), which the platform only provides from
        // API 26. On 24/25 the app would install and then die with
        // NoClassDefFoundError the first time it timestamped a screening.
        // The alternative is core-library desugaring, which needs
        // desugar_jdk_libs downloaded - and this module deliberately has no
        // dependency that requires a network to build. API 26 is Android 8.0.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Implementation, not testImplementation: record/sync JSON is used from
    // main source. On a real device the platform's own org.json classes take
    // over at runtime as usual; this artifact is what makes the same code
    // runnable in a plain JVM unit test, where Android's org.json is a stub
    // that throws "not mocked".
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}

// The golden-vector fixtures live outside this module - app/test/fixtures/, shared
// with the Python reference that generates them - and the tests reach them through a
// relative File() path. Gradle cannot see through that, so without this declaration
// `test` stays UP-TO-DATE after the vectors are regenerated and the suite silently
// does not run: a green build that proves nothing, which is the one failure mode the
// golden-vector discipline exists to prevent (CLAUDE.md, "the central architectural
// fact"). Verified by corrupting a fixture and confirming the build now fails.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("../../app/test/fixtures"))
        .withPropertyName("goldenVectors")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
