#!/usr/bin/env sh
# Refreshes the bundled disposable email domain list from upstream.
set -eu
cd "$(dirname "$0")/.."
list=src/main/resources/disposable-email-domains.txt
{
  echo "# Disposable email domains rejected by the email-domain-policy validator."
  echo "# Source: https://github.com/disposable-email-domains/disposable-email-domains (CC0-1.0)"
  echo "# Refresh with: scripts/update-domain-list.sh"
  curl -fsSL https://raw.githubusercontent.com/disposable-email-domains/disposable-email-domains/main/disposable_email_blocklist.conf
} > "$list.new"
mv "$list.new" "$list"
