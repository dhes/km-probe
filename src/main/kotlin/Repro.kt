import dev.ohs.fhir.model.r4.Resource
import kotlinx.serialization.json.Json

/** Minimal reproduction for the kotlin-fhir ExpressionLanguage issue. */
fun main() {
    val pd = """
        {
          "resourceType": "PlanDefinition",
          "status": "draft",
          "action": [{
            "condition": [{
              "kind": "applicability",
              "expression": { "language": "text/cql-identifier", "expression": "Eligibility status" }
            }]
          }]
        }
    """
    val json = Json { ignoreUnknownKeys = true }
    val resource = json.decodeFromString(Resource.serializer(), pd)
    println("Parsed: ${resource::class.simpleName}")
}
