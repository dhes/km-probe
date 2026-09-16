import dev.ohs.fhir.knowledge.FhirNpmPackage
import dev.ohs.fhir.knowledge.ImportResult
import dev.ohs.fhir.knowledge.KnowledgeManager
import java.io.File
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath

/**
 * Try-out of the KMP KnowledgeManager (ohs-foundation/kotlin-fhir-knowledge PR #2, branch
 * migrate-knowledge-to-kmp, dev.ohs.fhir:fhir-knowledge:2.0.0-alpha01 via mavenLocal) against the
 * WHO immunizations CI package: 712 resources, real `npm pack` layout — the scale and shape the
 * PR's anc-cds fixtures (6 artifacts) don't cover.
 *
 * Reports findings rather than asserting, so one failure doesn't hide the rest.
 */

// Directory holding the unpacked corpus packages; override via KM_PROBE_CORPUS.
val CORPUS: String =
    System.getenv("KM_PROBE_CORPUS") ?: "/Users/danheslinga/projects/smart-dak/corpus/unpacked"

val IMMUNIZATIONS = FhirNpmPackage("smart.who.int.immunizations", "0.2.0", "http://smart.who.int/immunizations")
val DAK_SKELETON = FhirNpmPackage("smart.who.int.dak-immz", "1.1.0", "http://smart.who.int/dak-immz")

// One known artifact per type, straight from the corpus files. PlanDefinition MISSED on
// fhir-model beta05 (closed ExpressionLanguage enum rejected text/cql-identifier, kotlin-fhir#123);
// fixed in rc03, which the PR #2 branch now pins.
val CANONICAL_SAMPLES =
    listOf(
        "http://smart.who.int/immunizations/Library/IMMZIND33Logic",
        "http://smart.who.int/immunizations/ValueSet/IMMZD18SMeaslesSupplementaryDoseVS",
        "http://smart.who.int/immunizations/ActivityDefinition/IMMZD2DTCR",
        "http://smart.who.int/immunizations/PlanDefinition/IMMZD2DTMeaslesMCVDose0",
    )

// A Library URL for the version-handling battery: Libraries index cleanly.
val VERSIONED_URL = CANONICAL_SAMPLES[0]

val ENUMERABLE_TYPES =
    listOf("Library", "ValueSet", "PlanDefinition", "Measure", "StructureDefinition",
        "StructureMap", "Questionnaire", "CodeSystem", "ImplementationGuide")

val findings = mutableListOf<String>()

fun report(r: ImportResult) {
    println("  ImportResult: indexed=${r.indexed} skipped=${r.skipped} failed=${r.failed.size}")
    r.failed.forEach { println("    failed: ${it.name}") }
}

fun expect(label: String, want: Int, got: Int) {
    val ok = want == got
    println("  [${if (ok) "PASS" else "FAIL"}] $label: got $got, want $want")
    if (!ok) findings += "$label: got $got, want $want"
}

fun main() = runBlocking {
    val km = KnowledgeManager.create(inMemory = true)

    // -- 1. Import the immunizations CI package (extracted-package path) --
    lateinit var immzResult: ImportResult
    val importNanos = measureNanoTime {
        immzResult = km.import(IMMUNIZATIONS, "$CORPUS/immunizations/package".toPath())
    }
    println("import(immunizations, 716 files): ${importNanos / 1_000_000} ms")
    report(immzResult)
    expect("immunizations import: failed files", 0, immzResult.failed.size)

    // -- 2. Index census per type (deprecated enumeration API is the only way to count) --
    @Suppress("DEPRECATION")
    val census = ENUMERABLE_TYPES.associateWith { km.loadResources(resourceType = it).count() }
    census.forEach { (type, n) -> println("  indexed $type: $n") }
    println("  indexed total: ${census.values.sum()} (corpus: 712 resources, 705 of enumerated types + 1 IG)")
    expect("Library census", 279, census["Library"] ?: 0)
    expect("ValueSet census", 192, census["ValueSet"] ?: 0)
    expect("PlanDefinition census (kotlin-fhir text/cql-identifier gap)", 138, census["PlanDefinition"] ?: 0)
    expect("Measure census (kotlin-fhir text/cql-identifier gap)", 41, census["Measure"] ?: 0)

    // -- 3. Canonical resolution, the $apply-critical path --
    println("canonical loadResources(url):")
    for (url in CANONICAL_SAMPLES) {
        var hit: Int
        val nanos = measureNanoTime { hit = km.loadResources(url).count() }
        expect("  ${url.substringAfterLast('/')} (${nanos / 1000} us)", 1, hit)
    }

    // -- 4. Version handling on a cleanly-indexed Library --
    println("version handling:")
    expect("  pipe URL |0.2.0", 1, km.loadResources("$VERSIONED_URL|0.2.0").count())
    expect("  explicit version 0.2.0", 1, km.loadResources(VERSIONED_URL, "0.2.0").count())
    expect("  wrong version 9.9.9", 0, km.loadResources(VERSIONED_URL, "9.9.9").count())
    expect("  unknown URL", 0, km.loadResources("http://smart.who.int/immunizations/Library/NoSuchThing").count())

    // -- 5. Second IG import; both must stay resolvable --
    val dakResult = km.import(DAK_SKELETON, "$CORPUS/dak-immz/package".toPath())
    report(dakResult)
    // The dak-immz ImplementationGuide carries license CC-BY-SA-3.0-IGO, absent from the R4 SPDX
    // value set (required binding), so it fails to parse. Expected: it must show up in `failed`.
    expect("dak-immz import: failed files (the IG, closed SPDXLicense enum)", 1, dakResult.failed.size)
    expect("cross-IG: immunizations Library after dak-immz import", 1, km.loadResources(VERSIONED_URL).count())

    // -- 6. Persistent index scoped by application id; re-import must be a no-op, not an FK crash --
    println("persistent index (PR #2 findings #2 and #6):")
    val appId = "dhes.km-probe"
    val appDir = File(System.getProperty("user.home"), ".fhir-knowledge/$appId")
    appDir.deleteRecursively()
    val persistent = KnowledgeManager.create(platformContext = appId)
    val first = persistent.import(IMMUNIZATIONS, "$CORPUS/immunizations/package".toPath())
    val second = persistent.import(IMMUNIZATIONS, "$CORPUS/immunizations/package".toPath())
    expect("  storage scoped under ~/.fhir-knowledge/$appId (knowledge.db present)", 1,
        if (File(appDir, "knowledge.db").isFile) 1 else 0)
    expect("  first import indexed", immzResult.indexed, first.indexed)
    expect("  second import of the same package: indexed (skip, no FK crash)", 0, second.indexed)
    @Suppress("DEPRECATION")
    expect("  PlanDefinition census after the double import", 138,
        persistent.loadResources(resourceType = "PlanDefinition").count())
    appDir.deleteRecursively()

    println()
    if (findings.isEmpty()) println("All checks passed.")
    else {
        println("${findings.size} finding(s):")
        findings.forEach { println("  - $it") }
    }
}
