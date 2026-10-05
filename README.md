# Keycloak Email Domain Policy

Control which email domains can be used for accounts in Keycloak.

- **Blocks disposable email**, such as `mailinator.com` and about 9,000
  other throwaway providers, out of the box.
- **Allows or blocks your own domains** per realm, subdomains included.
- **Allowlist-only mode**: "only `@company.com` may register".

## Why use it?

Most sign-up filters only check the registration form. This extension is a
**user profile validator**, so the policy applies everywhere Keycloak
validates an email:

- self-registration
- profile updates in the account console
- first sign-in through an external identity provider
- the admin console and admin REST API

It runs fully offline: the disposable list ships inside the JAR and nothing is
fetched at runtime. If you want a fresher list between releases, it can
reload a list file that you keep up to date yourself.

## Quick start

**1. Install.** Download the JAR from
[Releases](https://github.com/InsaneDwayne/keycloak-email-domain-policy/releases)
and put it into `/opt/keycloak/providers/`. For example, with Docker:

```dockerfile
FROM quay.io/keycloak/keycloak:26.2.5
ADD --chmod=644 --checksum=sha256:<sha> \
  https://github.com/InsaneDwayne/keycloak-email-domain-policy/releases/download/v0.1.0/keycloak-email-domain-policy-0.1.0.jar \
  /opt/keycloak/providers/
```

If you start Keycloak with `start --optimized`, run `kc.sh build` after adding
the JAR.

**2. Enable it for a realm.** In the admin console, open _Realm settings →
User profile → email → Validators_, add `email-domain-policy`, and save. With
no options, it blocks disposable email.

That's it. Try registering with a `@mailinator.com` address to see it reject
the email.

More setups: [examples/](examples/) has a Docker Compose file with the JAR
mounted and a full user profile you can apply with
`kcadm.sh update realms/<realm>/users/profile -f examples/user-profile.json`.

## Options

| Option                 | Default | What it does                                                                         |
| ---------------------- | ------- | ------------------------------------------------------------------------------------ |
| `block-disposable`     | `true`  | Reject domains on the disposable list.                                               |
| `allowed-domains`      | `[]`    | Always accept these domains, even if they're on the disposable list.                 |
| `blocked-domains`      | `[]`    | Always reject these domains.                                                         |
| `allowed-domains-only` | `false` | Reject every domain not in `allowed-domains`.                                        |
| `allow-current-email`  | `true`  | Let users keep an email they already have, even if the policy would now reject it.   |
| `error-message`        | none    | Custom error text or message key, shown instead of the default messages.             |

Domain entries also cover their subdomains: `company.com` matches
`mail.company.com`.

## Common setups

**Public sign-up**, block throwaway addresses:

```json
"email-domain-policy": {}
```

**Public sign-up**, fix a false positive and block one extra domain:

```json
"email-domain-policy": {
  "allowed-domains": ["example-partner.com"],
  "blocked-domains": ["spam-host.example"]
}
```

**Internal realm**, company addresses only, except contractors:

```json
"email-domain-policy": {
  "allowed-domains": ["company.com", "company.de"],
  "blocked-domains": ["contractors.company.com"],
  "allowed-domains-only": true,
  "error-message": "Please sign up with your company email."
}
```

## Learn more

[docs/configuration.md](docs/configuration.md) covers:

- how allowed and blocked entries are matched
- server-wide domain lists from files, for all realms
- keeping the disposable list up to date without a restart
- error messages and translations
- where the disposable list comes from
- FAQ

## Compatibility

Built for Keycloak 26.2.5 and later, on Java 21. It uses Keycloak's
internal validator SPI, which may change between Keycloak releases.
Each release is tested against the latest patch of every Keycloak minor
from 26.2 on; its [release notes](https://github.com/InsaneDwayne/keycloak-email-domain-policy/releases)
list the exact versions.

## Development

```sh
just build   # or: mvn verify
```

The JAR is written to `target/`. Integration tests against a real Keycloak
run with Docker Compose, see
[integration-tests/compose.yaml](integration-tests/compose.yaml). Releases are
described in [docs/releasing.md](docs/releasing.md). The dev container is
described in [docs/development.md](docs/development.md).

## Security

Please report vulnerabilities privately, not as issues; see
[SECURITY.md](SECURITY.md).

## License

[Apache-2.0](LICENSE)
