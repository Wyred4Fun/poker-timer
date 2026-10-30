plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub numbers each build; the app version follows it (1.0.1, 1.0.2, ...)
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.wyred4fun.pokertimer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wyred4fun.pokertimer"
        minSdk = 23
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // One fixed key so every new build installs over the old one
            storeFile = file("release.keystore")
            storePassword = "pokertimer"
            keyAlias = "pokertimer"
            keyPassword = "pokertimer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
