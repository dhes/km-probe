plugins {
    kotlin("jvm") version "2.3.10"
    application
}

repositories {
    mavenLocal() // dev.ohs.fhir:fhir-knowledge:2.0.0-alpha01 from the PR #2 branch
    mavenCentral()
    google() // androidx.sqlite / room3 runtimes the knowledge library depends on
}

// Acceptance runs for ohs-foundation/kotlin-fhir#123: override the model version to test a
// candidate fix, e.g.  ./gradlew run -PfhirModelVersion=1.0.0-rc03
// A fixed model should take the PlanDefinition census from 0 to 138 and Measures from 0 to 41.
val fhirModelVersion = providers.gradleProperty("fhirModelVersion").getOrElse("1.0.0-rc03") // PR #2 branch pins rc03 since 2026-09-14

// CI eval mode (-PciEval): fhir-knowledge:2.0.0-alpha01 exists only in mavenLocal (built
// from kotlin-fhir-knowledge PR #2), so CI drops it and the one source file that needs it
// (KmProbe.kt). The repro and scanner run on fhir-model alone.
val ciEval = providers.gradleProperty("ciEval").isPresent

dependencies {
    if (!ciEval) implementation("dev.ohs.fhir:fhir-knowledge:2.0.0-alpha01")
    // Workaround: fhir-knowledge returns model.r4.Resource in its public API but declares
    // fhir-model as implementation, not api — consumers must add it themselves to compile.
    implementation("dev.ohs.fhir:fhir-model:$fhirModelVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okio:okio:3.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") // for the Repro.kt minimal case
}

configurations.all {
    resolutionStrategy {
        // Make the transitive fhir-model (via fhir-knowledge) match the override too.
        force("dev.ohs.fhir:fhir-model:$fhirModelVersion")
    }
}

kotlin { jvmToolchain(21) } // fhir-knowledge desktop artifact is compiled for JVM 21

if (ciEval) {
    sourceSets["main"].kotlin.exclude("KmProbe.kt")
}

application { mainClass.set("KmProbeKt") }

tasks.register<JavaExec>("repro") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ReproKt")
}

// Corpus readiness scanner: ./fetch-corpus.sh then ./gradlew scan [-PfhirModelVersion=...]
tasks.register<JavaExec>("scan") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ScannerKt")
    systemProperty("fhirModelVersion", fhirModelVersion)
}

tasks.register<JavaExec>("scratchNpe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ScratchNpeKt")
}
