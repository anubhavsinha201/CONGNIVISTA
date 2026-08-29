plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arogyax.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arogyax.app"
        minSdk = 24
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
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
