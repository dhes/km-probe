# km-probe

Try-out of the KMP KnowledgeManager from
[ohs-foundation/kotlin-fhir-knowledge](https://github.com/ohs-foundation/kotlin-fhir-knowledge)
(the KMP Knowledge Manager) draft PR #2, against the WHO immunizations CI package
(`smart.who.int.immunizations` 0.2.0, 712 resources) — real scale and real `npm pack`
layout, beyond the PR's six-artifact `anc-cds` fixtures.

## What it exercises

1. `import()` of an extracted FHIR NPM package (716 files)
2. Index census per resource type
3. Canonical `loadResources(url)` — the `$apply`-critical path
4. Version handling: pipe URL, explicit version, wrong version, unknown URL
5. A second IG import (`smart.who.int.dak-immz` skeleton) with cross-IG resolution intact

## Running

Prereqs: the PR branch published locally —

    cd ~/ohs-foundation-repos/kotlin-fhir-knowledge   # branch migrate-knowledge-to-kmp
    ./gradlew :knowledge:publishDesktopPublicationToMavenLocal \
              :knowledge:publishKotlinMultiplatformPublicationToMavenLocal

and the corpus packages unpacked so that the layout is

    $KM_PROBE_CORPUS/
      immunizations/package/   # smart.who.int.immunizations 0.2.0 (CI build), 716 files
      dak-immz/package/        # smart.who.int.dak-immz 1.1.0 (skeleton)

Point `KM_PROBE_CORPUS` at that directory (defaults to the author's local path).

Then:

    ./gradlew run      # full battery
    ./gradlew repro    # minimal kotlin-fhir#123 reproduction (no corpus needed)

## Acceptance run for kotlin-fhir#123

To validate a candidate fix for
[ohs-foundation/kotlin-fhir#123](https://github.com/ohs-foundation/kotlin-fhir/issues/123),
override the model version:

    ./gradlew repro -PfhirModelVersion=<fixed-version>   # should print "Parsed: PlanDefinition"
    ./gradlew run   -PfhirModelVersion=<fixed-version>   # census: PlanDefinition 0 -> 138, Measure 0 -> 41

A fixed model turns the three expected `[FAIL]` findings into `[PASS]` and the import's
183 load errors into 4 (the non-resource files).
