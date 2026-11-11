plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val githubBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 3

android {
    namespace = "com.holdgrip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.holdgrip"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions run number increases automatically for every build.
        versionCode = githubBuildNumber
        versionName = "1.2.$githubBuildNumber"
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
}
