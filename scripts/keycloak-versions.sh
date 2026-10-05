#!/usr/bin/env sh
# Prints the Keycloak versions the integration tests run against, oldest first:
# the latest patch of every minor from keycloak.version in pom.xml (the lowest
# supported version) up to the newest Keycloak release.
#
# Reads the published GitHub releases, not the git tags: keycloak/keycloak has
# tags for patch versions that never got a release or a container image.
# Set GH_TOKEN to avoid the API's rate limit for anonymous requests.
set -eu
cd "$(dirname "$0")/.."
lowest=$(sed -n 's:.*<keycloak.version>\(.*\)</keycloak.version>.*:\1:p' pom.xml)

releases() {
  page=1
  while :; do
    tags=$(curl -fsSL ${GH_TOKEN:+-H "Authorization: Bearer $GH_TOKEN"} \
      "https://api.github.com/repos/keycloak/keycloak/releases?per_page=100&page=$page" \
      | sed -n 's/^ *"tag_name": *"\([0-9]*\.[0-9]*\.[0-9]*\)",$/\1/p')
    [ -n "$tags" ] || break
    echo "$tags"
    page=$((page + 1))
  done
}

{ releases; echo "$lowest"; } | sort -V -u | awk -v lowest="$lowest" '
  $0 == lowest { found = 1 }
  found {
    split($0, v, ".")
    minor = v[1] "." v[2]
    if (!(minor in latest)) order[++n] = minor
    latest[minor] = $0
  }
  END { for (i = 1; i <= n; i++) print latest[order[i]] }'
