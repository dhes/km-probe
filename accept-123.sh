#!/usr/bin/env bash
# Acceptance run for ohs-foundation/kotlin-fhir#123.
#
#   ./accept-123.sh            # test the newest fhir-model on Maven Central
#   ./accept-123.sh 1.0.0-rc03 # test a specific version
#
# Verdict logic: the #123 fix is present when the 11-line repro parses and the
# corpus import indexes all 138 PlanDefinitions and 41 Measures (they are 0/0
# on 1.0.0-beta05). Output ends with a paste-ready summary for the issue.
set -euo pipefail
cd "$(dirname "$0")"

KNOWN_BROKEN="1.0.0-rc02" # newest version verified to still lack the #123 fix

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION=$(curl -s 'https://repo1.maven.org/maven2/dev/ohs/fhir/fhir-model/maven-metadata.xml' \
    | grep -oE '<latest>[^<]+' | sed 's/<latest>//')
  echo "Newest fhir-model on Maven Central: $VERSION"
  if [ "$VERSION" = "$KNOWN_BROKEN" ]; then
    echo "No release newer than the known-broken $KNOWN_BROKEN yet - nothing to accept."
    echo "(Run again with an explicit version to test a snapshot/staging build.)"
    exit 0
  fi
fi

echo "== Acceptance run against dev.ohs.fhir:fhir-model:$VERSION =="

echo "-- 1. Minimal repro (text/cql-identifier PlanDefinition must parse) --"
if ./gradlew -q repro -PfhirModelVersion="$VERSION" 2>&1 | grep -q 'Parsed: PlanDefinition'; then
  REPRO=PASS
else
  REPRO=FAIL
fi
echo "   repro: $REPRO"

echo "-- 2. Full corpus import (712 resources) --"
RUN_OUT=$(./gradlew -q run -PfhirModelVersion="$VERSION" 2>/dev/null | grep -aE 'indexed (PlanDefinition|Measure):' || true)
echo "$RUN_OUT" | sed 's/^/   /'
PD=$(echo "$RUN_OUT" | grep -oE 'PlanDefinition: [0-9]+' | grep -oE '[0-9]+' || echo 0)
MEASURE=$(echo "$RUN_OUT" | grep -oE 'Measure: [0-9]+' | grep -oE '[0-9]+' || echo 0)

echo
if [ "$REPRO" = PASS ] && [ "$PD" = 138 ] && [ "$MEASURE" = 41 ]; then
  VERDICT="ACCEPTED"
else
  VERDICT="NOT FIXED"
fi

cat <<SUMMARY
== VERDICT: $VERDICT ==

Paste-ready for kotlin-fhir#123:
---
Acceptance run against \`fhir-model:$VERSION\` (dhes/km-probe, WHO
\`smart.who.int.immunizations\` 0.2.0 CI corpus, 712 resources):
- minimal \`text/cql-identifier\` repro: $REPRO
- PlanDefinitions indexed: $PD / 138
- Measures indexed: $MEASURE / 41
---
SUMMARY
