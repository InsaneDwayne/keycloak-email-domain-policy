package io.github.insanedwayne.keycloak.emaildomainpolicy;

/**
 * Decides whether an email's domain may be used. Free of Keycloak types.
 *
 * <p>Decision order:
 * <ol>
 *   <li>The most specific entry of {@code allowed} or {@code blocked} matching the domain decides;
 *       if both lists hold that entry, blocked wins.</li>
 *   <li>In allowlist-only mode, a domain no allowed entry covers is rejected.</li>
 *   <li>A domain on the disposable list is rejected.</li>
 *   <li>Everything else is accepted.</li>
 * </ol>
 *
 * @param allowed     domains to accept, with their subdomains
 * @param blocked     domains to reject, with their subdomains
 * @param allowedOnly reject every domain {@code allowed} doesn't cover
 * @param disposable  disposable domains to reject; {@link DomainSet#EMPTY} turns the check off
 */
record EmailDomainPolicy(DomainSet allowed, DomainSet blocked, boolean allowedOnly, DomainSet disposable) {

    enum Decision {
        ACCEPTED, BLOCKED, NOT_ALLOWED, DISPOSABLE
    }

    Decision check(String email) {
        String domain = domainOf(email);
        if (domain == null) {
            // Not an email address; the email format validator reports that.
            return Decision.ACCEPTED;
        }
        for (String candidate : DomainSet.candidates(DomainSet.normalize(domain))) {
            if (blocked.containsExactly(candidate)) {
                return Decision.BLOCKED;
            }
            if (allowed.containsExactly(candidate)) {
                return Decision.ACCEPTED;
            }
        }
        if (allowedOnly) {
            return Decision.NOT_ALLOWED;
        }
        if (disposable.matches(domain)) {
            return Decision.DISPOSABLE;
        }
        return Decision.ACCEPTED;
    }

    /** The part after the last {@code @}, or {@code null} if there is none. */
    static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        return at < 0 || at == email.length() - 1 ? null : email.substring(at + 1);
    }
}
