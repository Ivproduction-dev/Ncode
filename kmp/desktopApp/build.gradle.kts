plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(project(":shared"))
    implementation(files("../libs/jlayer.jar", "../libs/jorbis.jar", "../libs/jaad.jar"))
}

sourceSets {
    main {
        kotlin.srcDir("../../ncode")
        kotlin.include("Desktop.kt", "Ide.kt")
    }
}

application {
    mainClass.set("ncode.DesktopKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
