# Security policy

## Supported versions

Only the latest release gets security fixes. Please upgrade before reporting.

## Reporting a vulnerability

Please don't open a public issue. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/InsaneDwayne/keycloak-email-domain-policy/security/advisories/new).

Include what you can of:

- the extension and Keycloak versions
- the validator configuration (user profile JSON) and any server options
- steps to reproduce, and what an attacker gains

This is a spare-time project. You'll usually get a first answer within a
week. Once a fix is released, the advisory is published and you're credited
if you want to be.

## Scope

In scope: ways to get around the policy, for example an email whose domain
is checked differently than Keycloak stores it, a crash or excessive load
caused by user input, or anything that affects Keycloak beyond this
validator.

Not security issues, please open a normal issue:

- a disposable domain missing from the bundled list, or a real domain wrongly
  on it. The list comes from
  [disposable-email-domains](https://github.com/disposable-email-domains/disposable-email-domains);
  report it there, and use `allowed-domains` or `blocked-domains` in the
  meantime.
- behaviour that is documented, such as users keeping their current email
  when `allow-current-email` is on.

Vulnerabilities in Keycloak itself go to the Keycloak project, see its
[security policy](https://github.com/keycloak/keycloak/security/policy).
