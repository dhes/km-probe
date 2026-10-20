# km-probe

Corpus-acceptance and readiness harness for kotlin-fhir against WHO smart-* content.

## Commands

- `./accept-123.sh <version>` — acceptance run against a given `dev.ohs.fhir:fhir-model`
  version. Pass criteria: verdict `ACCEPTED`, minimal text/cql-identifier repro PASS,
  138/138 PlanDefinitions and 41/41 Measures indexed. Corpus =
  `smart.who.int.immunizations` 0.2.0 CI package, 712 resources.
- `./gradlew scan -PfhirModelVersion=<V>` — 31-package readiness scan; writes READINESS.md.
  Resolves from Maven Central directly.

## Baselines

rc02 baseline (READINESS-rc02-baseline.md): 259 resources blocked by kotlin-fhir #123.
Expected post-#123 result: 0 blocked; other-fails column unchanged (smart-trust 464,
smart-trust-phw 48, smart-pcmt-vaxprequal 10, ~1 each elsewhere).

## Verifying your work

- Acceptance: `./accept-123.sh <V>` must end `== VERDICT: ACCEPTED ==` with
  `repro: PASS`, `indexed PlanDefinition: 138`, `indexed Measure: 41`.
- Scan: `./gradlew scan -PfhirModelVersion=<V>` then diff READINESS.md against
  READINESS-rc02-baseline.md — 0 blocked, other-fails column unchanged.
- Run the relevant one before reporting done, and paste the output.

## CI eval

`.github/workflows/corpus-eval.yml` runs weekly (Mon 16:00 UTC) and on dispatch:
tripwire on any fhir-model version newer than its `KNOWN_VERSION`, repro + full scan,
loud failure on a new version. `-PciEval` drops the mavenLocal-only fhir-knowledge dep
and excludes KmProbe.kt — so the CI cannot run the 138/41 KM census; that stays local
via accept-123.sh. On accepting a new version: bump `KNOWN_VERSION` in the workflow and
commit a fresh READINESS-<ver>-baseline.md.

## Gotchas

- Testing an unreleased kotlin-fhir build: publish **all** modules to mavenLocal, not just
  fhir-model — partial publishes resolve stale transitive modules. Signing must be disabled
  locally (vanniktech plugin signs everything): use an init script that disables Sign tasks.
- ELM in the corpus is XML, not JSON — except smart-anc, whose CI-fetched package (0.3.0, 2026-10-19) carries
  `application/elm+json` on 63 Libraries.

## Open items (2026-10-19)

- Refresh the local corpus (`./fetch-corpus.sh`) and regenerate READINESS-rc04-baseline.md from it. The baseline is
  still the 2026-08-19 corpus, so the weekly CI run reports drift (e.g. smart-anc newly published) as a warning.
- smart-anc now publishes (build.fhir.org CI, 0.3.0): 40 PlanDefinitions, 13 Measures, 64 Libraries, 63 with
  `application/elm+json`; 38 resources fail to parse on rc04 (mostly test data missing required elements). Check
  whether its ELM records a `translatorVersion` and loads; candidate second corpus for the CQL spike.
