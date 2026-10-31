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
        minSdk = 23
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    buildFeatures { buildConfig = true }

    // Two apps from the same code: the club billboard (keeps the original app ID, so the club TV
    // updates in place) and the plain poker timer (its own ID, so both can be installed).
    flavorDimensions += "edition"
    productFlavors {
        create("club") {
            dimension = "edition"
            applicationId = "com.wyred4fun.pokertimer"
            resValue("string", "app_name", "Catalpa Club Poker Timer")
            buildConfigField("boolean", "CLUB", "true")
            buildConfigField("boolean", "SIGNAGE", "false")
            buildConfigField("String", "RELEASE_TAG", "\"build-\"")
        }
        create("poker") {
            dimension = "edition"
            applicationId = "com.wyred4fun.pokertimer.poker"
            resValue("string", "app_name", "Poker Timer")
            buildConfigField("boolean", "CLUB", "false")
            buildConfigField("boolean", "SIGNAGE", "false")
            buildConfigField("String", "RELEASE_TAG", "\"poker-\"")
        }
        // the billboard on its own: slides only, no timer (for the other screens in the club)
        create("signage") {
            dimension = "edition"
            applicationId = "com.wyred4fun.pokertimer.signage"
            resValue("string", "app_name", "Digital Billboard")
            buildConfigField("boolean", "CLUB", "false")
            buildConfigField("boolean", "SIGNAGE", "true")
            buildConfigField("String", "RELEASE_TAG", "\"signage-\"")
        }
    }

    // the billboard-only app shows the same slides as the club app
    sourceSets { getByName("signage") { assets.srcDir("src/club/assets") } }

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
