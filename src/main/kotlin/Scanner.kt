import dev.ohs.fhir.model.r4.Resource
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * WHO SMART corpus readiness scanner — the km-probe try-out generalized from one package
 * (immunizations) to the whole WorldHealthOrganization smart-* CI corpus fetched by
 * ./fetch-corpus.sh.
 *
 * Per package it reports, from the raw JSON (no model dependency):
 *   - resource census by type
 *   - Expression.language census (text/cql-identifier vs text/cql vs text/fhirpath ...)
 *   - Library.content contentType census (text/cql vs application/elm+xml vs elm+json)
 *   - dependency list from package.json
 * and, against dev.ohs.fhir:fhir-model (version via -PfhirModelVersion, default in
 * build.gradle.kts): a parse attempt per resource, with failures classified as
 * kotlin-fhir#123 (closed ExpressionLanguage enum) vs other.
 *
 * Output: READINESS.md (ranked matrix + notes) and scan-results.json (full data).
 */

val SCAN_CORPUS: String = System.getenv("KM_SCAN_CORPUS") ?: "corpus-scan"

// The Expression.language values worth counting. R4 binds this EXTENSIBLY; the corpus uses
// the CQL-usage codes beyond the base three. Dot variants appear in older content.
val EXPRESSION_LANGUAGES = setOf(
    "text/cql", "text/cql-identifier", "text/cql-expression",
    "text/cql.identifier", "text/cql.expression",
    "text/fhirpath", "application/x-fhir-query", "text/x-fhir-query",
)

// Languages the deployed FHIRPath-only runtimes (e.g. the Ona/OpenSRP base) cannot execute.
val CQL_LANGUAGES = EXPRESSION_LANGUAGES - setOf("text/fhirpath", "application/x-fhir-query", "text/x-fhir-query")

val lenientJson = Json { ignoreUnknownKeys = true }

class PackageScan(val repo: String) {
    var name = ""
    var version = ""
    var fhirVersion = ""
    var dependencies = mapOf<String, String>()
    var resourceFiles = 0
    var nonResourceFiles = 0
    val census = sortedMapOf<String, Int>()
    val exprLanguages = sortedMapOf<String, Int>()          // occurrences
    val cqlUsingByType = sortedMapOf<String, Int>()          // resources containing a CQL-family expression
    val libraryContentTypes = sortedMapOf<String, Int>()
    var parseOk = 0
    var parseFail123 = 0
    var parseFailOther = 0
    val otherFailMessages = sortedMapOf<String, Int>()       // exception summary -> count

    val totalResources get() = census.values.sum()
    val cqlUsingTotal get() = cqlUsingByType.values.sum()
    val decisionLogicCount get() = (census["PlanDefinition"] ?: 0) + (census["Measure"] ?: 0) + (census["Library"] ?: 0)
}

/** Walk the JSON tree counting Expression.language values (objects with a `language` field
 * whose value is one of the known expression-language codes — distinctive enough that
 * Resource.language/Attachment.language ("en", ...) never collide). */
fun walkExpressions(el: JsonElement, counts: MutableMap<String, Int>) {
    when (el) {
        is JsonObject -> {
            val lang = (el["language"] as? JsonPrimitive)?.content
            if (lang != null && lang in EXPRESSION_LANGUAGES) counts.merge(lang, 1, Int::plus)
            el.values.forEach { walkExpressions(it, counts) }
        }
        is JsonArray -> el.forEach { walkExpressions(it, counts) }
        else -> {}
    }
}

/** One line summarizing a non-#123 parse failure, for grouping. Message-less failures (NPEs)
 * get the file name instead so they stay individually identifiable for repro. */
fun failureKey(e: Throwable, fileName: String): String {
    val msg = e.message?.lineSequence()?.first()
        ?: return "${e::class.simpleName} @ $fileName"
    return "${e::class.simpleName}: ${msg.take(140)}"
}

fun scanPackage(repo: String, packageDir: File): PackageScan {
    val scan = PackageScan(repo)

    File(packageDir, "package.json").takeIf { it.isFile }?.let { pkg ->
        val obj = lenientJson.parseToJsonElement(pkg.readText()) as? JsonObject ?: return@let
        scan.name = (obj["name"] as? JsonPrimitive)?.content ?: ""
        scan.version = (obj["version"] as? JsonPrimitive)?.content ?: ""
        scan.fhirVersion = (obj["fhirVersions"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString(",") ?: ""
        scan.dependencies = (obj["dependencies"] as? JsonObject)
            ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
    }

    // Top-level files only, mirroring KnowledgeManager's census semantics (example/, other/,
    // xml/ subdirectories are publisher by-products, not package content).
    val files = packageDir.listFiles { f: File -> f.isFile }?.sortedBy { it.name } ?: emptyList()
    for (file in files) {
        if (!file.name.endsWith(".json") || file.name == "package.json" || file.name.startsWith(".index")) {
            scan.nonResourceFiles++
            continue
        }
        val text = file.readText()
        val root = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val resourceType = (root?.get("resourceType") as? JsonPrimitive)?.content
        if (resourceType == null) { scan.nonResourceFiles++; continue }

        scan.resourceFiles++
        scan.census.merge(resourceType, 1, Int::plus)

        val exprCounts = mutableMapOf<String, Int>()
        walkExpressions(root, exprCounts)
        exprCounts.forEach { (lang, n) -> scan.exprLanguages.merge(lang, n, Int::plus) }
        if (exprCounts.keys.any { it in CQL_LANGUAGES }) {
            scan.cqlUsingByType.merge(resourceType, 1, Int::plus)
        }

        if (resourceType == "Library") {
            (root["content"] as? JsonArray)?.forEach { c ->
                ((c as? JsonObject)?.get("contentType") as? JsonPrimitive)?.content
                    ?.let { scan.libraryContentTypes.merge(it, 1, Int::plus) }
            }
        }

        runCatching { lenientJson.decodeFromString(Resource.serializer(), text) }
            .onSuccess { scan.parseOk++ }
            .onFailure { e ->
                val chain = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
                if ("ExpressionLanguage" in chain || "text/cql" in chain) scan.parseFail123++
                else { scan.parseFailOther++; scan.otherFailMessages.merge(failureKey(e, file.name), 1, Int::plus) }
            }
    }
    return scan
}

fun main() {
    val corpusDir = File(SCAN_CORPUS)
    require(corpusDir.isDirectory) { "Corpus dir $SCAN_CORPUS not found — run ./fetch-corpus.sh first" }
    val modelVersion = System.getProperty("fhirModelVersion") ?: "(build default)"

    val misses = mutableListOf<String>()
    val sources = mutableMapOf<String, String>()
    File(corpusDir, "manifest.tsv").takeIf { it.isFile }?.readLines()?.drop(1)?.forEach {
        val cols = it.split('\t')
        if (cols.size >= 2 && cols[1] != "OK") misses += "${cols[0]} (${cols[1]})"
        else if (cols.size >= 3) sources[cols[0]] = cols[2]
    }

    val scans = corpusDir.listFiles { f: File -> f.isDirectory }!!.sortedBy { it.name }
        .mapNotNull { repoDir ->
            val pkg = File(repoDir, "package")
            if (!pkg.isDirectory) return@mapNotNull null
            print("scanning ${repoDir.name} ... ")
            val s = scanPackage(repoDir.name, pkg)
            println("${s.totalResources} resources, ${s.cqlUsingTotal} CQL-using, ${s.parseFail123} blocked by #123")
            s
        }

    writeResultsJson(scans, modelVersion)
    writeReadinessMd(scans, misses, sources, modelVersion)
    println("\nWrote READINESS.md and scan-results.json (model: $modelVersion)")
}

fun writeResultsJson(scans: List<PackageScan>, modelVersion: String) {
    val out = buildJsonObject {
        put("fhirModelVersion", modelVersion)
        putJsonObject("packages") {
            for (s in scans) putJsonObject(s.repo) {
                put("name", s.name); put("version", s.version); put("fhirVersion", s.fhirVersion)
                put("resources", s.totalResources)
                putJsonObject("census") { s.census.forEach { (k, v) -> put(k, v) } }
                putJsonObject("expressionLanguages") { s.exprLanguages.forEach { (k, v) -> put(k, v) } }
                putJsonObject("cqlUsingResourcesByType") { s.cqlUsingByType.forEach { (k, v) -> put(k, v) } }
                putJsonObject("libraryContentTypes") { s.libraryContentTypes.forEach { (k, v) -> put(k, v) } }
                putJsonObject("parse") {
                    put("ok", s.parseOk); put("blockedBy123", s.parseFail123); put("otherFailures", s.parseFailOther)
                    putJsonObject("otherFailureKinds") { s.otherFailMessages.forEach { (k, v) -> put(k, v) } }
                }
                putJsonObject("dependencies") { s.dependencies.forEach { (k, v) -> put(k, v) } }
            }
        }
    }
    File("scan-results.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out))
}

fun writeReadinessMd(scans: List<PackageScan>, misses: List<String>, sources: Map<String, String>, modelVersion: String) {
    val content = scans.filter { it.totalResources > 0 }
    val ranked = content.sortedWith(
        compareByDescending<PackageScan> { it.cqlUsingTotal }.thenByDescending { it.decisionLogicCount })

    val totalRes = content.sumOf { it.totalResources }
    val totalCqlUsing = content.sumOf { it.cqlUsingTotal }
    val totalBlocked = content.sumOf { it.parseFail123 }
    val totalPd = content.sumOf { it.census["PlanDefinition"] ?: 0 }
    val totalMeasure = content.sumOf { it.census["Measure"] ?: 0 }
    val blockedRepos = content.count { it.parseFail123 > 0 }

    val md = buildString {
        appendLine("# WHO SMART corpus readiness matrix")
        appendLine()
        appendLine("Generated by `./gradlew scan` over the packages fetched by `./fetch-corpus.sh` for every")
        appendLine("`WorldHealthOrganization/smart-*` repo. Source per repo (see `corpus-scan/manifest.tsv`):")
        appendLine("`ci` = build.fhir.org auto-builder tip, `ghpages` = WHO's own CI tip on github.io,")
        appendLine("`canonical` = current publication on smart.who.int, `registry` = packages2.fhir.org.")
        appendLine("Parse columns: `dev.ohs.fhir:fhir-model:$modelVersion` (R4 model); \"blocked #123\" counts resources")
        appendLine("rejected by the closed `ExpressionLanguage` enum")
        appendLine("([ohs-foundation/kotlin-fhir#123](https://github.com/ohs-foundation/kotlin-fhir/issues/123)).")
        appendLine("R5 packages (marked ⚠) are expected to fail against the R4 model — their \"other parse fails\"")
        appendLine("are version mismatch, not defects.")
        appendLine()
        appendLine("## Headline")
        appendLine()
        appendLine("- ${content.size} smart-* repos publish a CI package; ${misses.size} have none (non-IG repos or broken CI).")
        appendLine("- $totalRes resources total; **$totalCqlUsing use CQL-family expressions** " +
            "(`text/cql-identifier` et al.) that no deployed FHIRPath-only runtime can execute.")
        appendLine("- **$totalBlocked resources across $blockedRepos repos fail to parse on kotlin-fhir $modelVersion " +
            "solely due to #123** — including $totalPd PlanDefinitions and $totalMeasure Measures corpus-wide.")
        appendLine()
        appendLine("## Matrix (ranked by CQL-using resources — decision-logic density)")
        appendLine()
        appendLine("| repo | version | source | resources | PD | Measure | Library | CQL-using | cql-identifier exprs | fhirpath exprs | blocked #123 | other parse fails | CQL src | ELM |")
        appendLine("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|")
        for (s in ranked) {
            val cqlIdExprs = (s.exprLanguages["text/cql-identifier"] ?: 0) + (s.exprLanguages["text/cql.identifier"] ?: 0)
            val fhirpathExprs = s.exprLanguages["text/fhirpath"] ?: 0
            val cqlSrc = if ((s.libraryContentTypes["text/cql"] ?: 0) > 0) "yes" else "-"
            val elm = buildList {
                if ((s.libraryContentTypes["application/elm+xml"] ?: 0) > 0) add("xml")
                if ((s.libraryContentTypes["application/elm+json"] ?: 0) > 0) add("json")
            }.ifEmpty { listOf("-") }.joinToString("+")
            val r5Mark = if (s.fhirVersion.isNotEmpty() && !s.fhirVersion.startsWith("4")) " ⚠R5" else ""
            appendLine("| ${s.repo}$r5Mark | ${s.version} | ${sources[s.repo] ?: "?"} | ${s.totalResources} | ${s.census["PlanDefinition"] ?: 0} " +
                "| ${s.census["Measure"] ?: 0} | ${s.census["Library"] ?: 0} | ${s.cqlUsingTotal} | $cqlIdExprs " +
                "| $fhirpathExprs | ${s.parseFail123} | ${s.parseFailOther} | $cqlSrc | $elm |")
        }
        appendLine()
        appendLine("## Per-repo notes")
        appendLine()
        for (s in ranked) {
            appendLine("### ${s.repo} — `${s.name}` ${s.version}")
            appendLine()
            appendLine("- census: " + s.census.entries.joinToString { "${it.key} ${it.value}" })
            if (s.exprLanguages.isNotEmpty())
                appendLine("- expression languages: " + s.exprLanguages.entries.joinToString { "`${it.key}` ×${it.value}" })
            if (s.cqlUsingByType.isNotEmpty())
                appendLine("- CQL-using resources: " + s.cqlUsingByType.entries.joinToString { "${it.key} ${it.value}" })
            if (s.libraryContentTypes.isNotEmpty())
                appendLine("- Library attachments: " + s.libraryContentTypes.entries.joinToString { "`${it.key}` ×${it.value}" })
            appendLine("- parse (fhir-model $modelVersion): ${s.parseOk} ok, ${s.parseFail123} blocked by #123, ${s.parseFailOther} other")
            s.otherFailMessages.forEach { (k, v) -> appendLine("    - $v× $k") }
            if (s.dependencies.isNotEmpty())
                appendLine("- dependencies: " + s.dependencies.entries.joinToString { "${it.key}@${it.value}" })
            appendLine()
        }
        if (misses.isNotEmpty()) {
            appendLine("## Repos without a CI package")
            appendLine()
            misses.forEach { appendLine("- $it") }
        }
    }
    File("READINESS.md").writeText(md)
}
