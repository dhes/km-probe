plugins {
    kotlin("jvm") version "2.3.10"
    application
}

repositories {
    mavenLocal() // dev.ohs.fhir:fhir-knowledge:2.0.0-alpha01 from the PR #2 branch
    mavenCentral()
    google() // androidx.sqlite / room3 runtimes the knowledge library depends on
}

dependencies {
    implementation("dev.ohs.fhir:fhir-knowledge:2.0.0-alpha01")
    // Workaround: fhir-knowledge returns model.r4.Resource in its public API but declares
    // fhir-model as implementation, not api — consumers must add it themselves to compile.
    implementation("dev.ohs.fhir:fhir-model:1.0.0-beta05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okio:okio:3.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") // for the Repro.kt minimal case
}

kotlin { jvmToolchain(21) } // fhir-knowledge desktop artifact is compiled for JVM 21

application { mainClass.set("KmProbeKt") }

tasks.register<JavaExec>("repro") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ReproKt")
}
