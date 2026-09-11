plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation("com.badlogicgames.gdx:gdx:1.12.1")
    implementation("com.badlogicgames.gdx:gdx-freetype:1.12.1")
}

sourceSets {
    main {
        kotlin.srcDir("../../ncode")
        kotlin.exclude("Desktop.kt", "Ide.kt", "GdxDesktop.kt")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
