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

## Gotchas

- Testing an unreleased kotlin-fhir build: publish **all** modules to mavenLocal, not just
  fhir-model — partial publishes resolve stale transitive modules. Signing must be disabled
  locally (vanniktech plugin signs everything): use an init script that disables Sign tasks.
- ELM in the corpus is XML, not JSON.
