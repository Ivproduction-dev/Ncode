plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "ncode.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ncode.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(files("../libs/jlayer.jar", "../libs/jorbis.jar", "../libs/jaad.jar"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:1.12.1")
    implementation("com.badlogicgames.gdx:gdx-freetype:1.12.1")
}
