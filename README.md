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

    ./accept-123.sh                    # newest Maven Central version, full verdict
    ./accept-123.sh 1.0.0-rc03         # or a specific version

The script runs the minimal repro plus the full corpus import and prints a
paste-ready summary for the issue. (Manual equivalents:
`./gradlew repro -PfhirModelVersion=...` and `./gradlew run -PfhirModelVersion=...`.)

A fixed model turns the three expected `[FAIL]` findings into `[PASS]` and the import's
183 load errors into 4 (the non-resource files).

## WHO SMART corpus readiness scanner

The probe generalized from one package to the whole `WorldHealthOrganization/smart-*`
corpus: fetch a package for every repo, then report per package — resource census,
`Expression.language` census (`text/cql-identifier` vs `text/fhirpath` ...),
`Library.content` attachment types (CQL source vs ELM), dependencies, and a per-resource
kotlin-fhir parse with failures classified as
[kotlin-fhir#123](https://github.com/ohs-foundation/kotlin-fhir/issues/123) vs other.

    ./fetch-corpus.sh                            # all smart-* repos -> corpus-scan/
    ./gradlew scan -PfhirModelVersion=1.0.0-rc02 # -> READINESS.md + scan-results.json

`fetch-corpus.sh` tries, in order: the build.fhir.org auto-builder tip, WHO's own CI tip
on `worldhealthorganization.github.io`, the current publication on `smart.who.int`, and
the packages2.fhir.org registry; `corpus-scan/manifest.tsv` records which one each repo
got. Needs `gh` (repo enumeration) and `python3` (package.json fields).

[READINESS.md](READINESS.md) is the committed output: the ranked matrix plus per-repo
notes. Re-run both commands to refresh it — e.g. after a kotlin-fhir release, where the
"blocked #123" column collapsing to zero is the corpus-scale acceptance signal.
