#!/usr/bin/env bash
# Fetch a package for every WorldHealthOrganization/smart-* IG into corpus-scan/,
# one directory per repo, plus a manifest of sources, hits, and misses.
#
#   ./fetch-corpus.sh              # fetch all smart-* repos
#   ./fetch-corpus.sh smart-hiv    # fetch (or refresh) a single repo
#
# Source order per repo (first hit wins; recorded in the manifest):
#   ci        build.fhir.org/ig/WorldHealthOrganization/<repo>/package.tgz  (auto-builder tip)
#   ghpages   worldhealthorganization.github.io/<repo>/package.tgz          (WHO's own CI tip)
#   canonical smart.who.int/<slug>/package.tgz                              (current publication)
#   registry  packages2.fhir.org/web/<pkgid>-<ver>.tgz                      (may lag canonical)
# Repos with none of the three (non-IG repos, never-built IGs) are MISS.
set -uo pipefail
cd "$(dirname "$0")"

DEST="${KM_SCAN_CORPUS:-corpus-scan}"
mkdir -p "$DEST"
MANIFEST="$DEST/manifest.tsv"

if [ $# -gt 0 ]; then
  REPOS="$*"
else
  REPOS=$(gh api --paginate 'orgs/WorldHealthOrganization/repos?per_page=100' \
    --jq '.[] | select(.name | startswith("smart")) | .name' | sort)
  printf 'repo\tstatus\tsource\tpackage\tversion\tfhirVersion\n' > "$MANIFEST"
fi

pkg_field() { # file key
  python3 -c "import json,sys;v=json.load(open(sys.argv[1])).get(sys.argv[2],'');print(','.join(v) if isinstance(v,list) else v)" "$1" "$2" 2>/dev/null || echo "?"
}

fetch() { # url -> $tgz ; returns curl status
  curl -fsSL --connect-timeout 15 --max-time 300 -o "$tgz" "$1" 2>/dev/null
}

for repo in $REPOS; do
  slug="${repo#smart-}"
  tgz="$DEST/$repo.tgz"
  source=""

  if fetch "https://build.fhir.org/ig/WorldHealthOrganization/$repo/package.tgz"; then
    source="ci"
  elif fetch "https://worldhealthorganization.github.io/$repo/package.tgz"; then
    source="ghpages"
  elif fetch "https://smart.who.int/$slug/package.tgz"; then
    source="canonical"
  else
    reg=$(curl -fsSL --connect-timeout 15 "https://packages2.fhir.org/packages/catalog?name=smart.who.int.$slug" 2>/dev/null \
      | python3 -c "
import json,sys
want='smart.who.int.'+sys.argv[1]
for e in json.load(sys.stdin):
    if e.get('name')==want: print(e['url']); break" "$slug" 2>/dev/null)
    if [ -n "$reg" ] && fetch "$reg"; then
      source="registry"
    fi
  fi

  if [ -z "$source" ]; then
    printf '%s\tMISS\t\t\t\t\n' "$repo" >> "$MANIFEST"
    echo "MISS  $repo  (no ci/canonical/registry package)"
    rm -f "$tgz"
    continue
  fi

  rm -rf "$DEST/$repo"
  mkdir -p "$DEST/$repo"
  if tar -xzf "$tgz" -C "$DEST/$repo" 2>/dev/null && [ -f "$DEST/$repo/package/package.json" ]; then
    pkg_json="$DEST/$repo/package/package.json"
    name=$(pkg_field "$pkg_json" name)
    version=$(pkg_field "$pkg_json" version)
    fhirv=$(pkg_field "$pkg_json" fhirVersions)
    printf '%s\tOK\t%s\t%s\t%s\t%s\n' "$repo" "$source" "$name" "$version" "$fhirv" >> "$MANIFEST"
    echo "OK    $repo  ($name $version, $source)"
  else
    printf '%s\tEXTRACT_FAIL\t%s\t\t\t\n' "$repo" "$source" >> "$MANIFEST"
    echo "FAIL  $repo  ($source tgz extract failed)"
    rm -rf "$DEST/$repo"
  fi
  rm -f "$tgz"
done

echo
echo "Corpus at $DEST/ — manifest: $MANIFEST"
