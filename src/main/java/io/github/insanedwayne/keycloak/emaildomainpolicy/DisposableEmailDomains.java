package io.github.insanedwayne.keycloak.emaildomainpolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The disposable email domains bundled in the JAR as {@value #RESOURCE}
 * (one domain per line, {@code #} starts a comment), read once on first use.
 */
final class DisposableEmailDomains {

    static final String RESOURCE = "/disposable-email-domains.txt";

    private DisposableEmailDomains() {
    }

    static DomainSet bundled() {
        return Holder.BUNDLED;
    }

    private static final class Holder {
        static final DomainSet BUNDLED = load();

        private static DomainSet load() {
            try (InputStream in = DisposableEmailDomains.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is missing from the provider JAR");
                }
                return DomainSet.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + RESOURCE, e);
            }
        }
    }
}
