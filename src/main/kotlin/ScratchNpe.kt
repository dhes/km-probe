import dev.ohs.fhir.model.r4.Resource
import java.io.File
import kotlinx.serialization.json.Json

/** Throwaway: capture the stack trace of the smart-ot Bundle NPE (corpus-scan finding). */
fun main() {
    val file = File(
        System.getenv("NPE_FILE")
            ?: "corpus-scan/smart-ot/package/Bundle-example-document-bundle-case-report-1.json"
    )
    try {
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(Resource.serializer(), file.readText())
        println("PARSED OK: ${r::class.simpleName}")
    } catch (e: Throwable) {
        var t: Throwable? = e
        while (t != null) {
            println("${t::class.simpleName}: ${t.message}")
            t.stackTrace.take(12).forEach { println("    at $it") }
            t = t.cause
            if (t != null) println("caused by:")
        }
    }
}
