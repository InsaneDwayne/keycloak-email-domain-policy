package io.github.insanedwayne.keycloak.emaildomainpolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A set of domains, each covering itself and all its subdomains. Entries and
 * looked-up domains share one normalisation: trimmed, lower-case, no trailing
 * dot, IDN converted to punycode.
 *
 * <p>Sets are immutable; {@link #plus} combines two without copying, so a large
 * list can be shared between many small per-realm sets.
 */
final class DomainSet {

    static final DomainSet EMPTY = new DomainSet(List.of());

    private static final Pattern LABEL = Pattern.compile("[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?");

    private final List<Set<String>> sets;

    private DomainSet(List<Set<String>> sets) {
        this.sets = sets;
    }

    /** Builds a set from entries, which must already be valid (see {@link #isValidEntry}). */
    static DomainSet of(Collection<String> entries) {
        Set<String> domains = new HashSet<>();
        for (String entry : entries) {
            String domain = normalize(entry);
            if (!domain.isEmpty()) {
                domains.add(domain);
            }
        }
        return domains.isEmpty() ? EMPTY : new DomainSet(List.of(Set.copyOf(domains)));
    }

    /** Reads one domain per line; blank lines and lines starting with {@code #} are skipped. */
    static DomainSet read(InputStream in) throws IOException {
        List<String> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                if (!isValidEntry(entry)) {
                    throw new IOException("Invalid domain: " + entry);
                }
                entries.add(entry);
            }
        }
        return of(entries);
    }

    static DomainSet read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        } catch (IOException e) {
            throw new IOException("Could not read domain list " + file + ": " + e.getMessage(), e);
        }
    }

    /** A set holding the entries of both sets. */
    DomainSet plus(DomainSet other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        List<Set<String>> combined = new ArrayList<>(sets);
        combined.addAll(other.sets);
        return new DomainSet(List.copyOf(combined));
    }

    boolean isEmpty() {
        return sets.isEmpty();
    }

    int size() {
        return sets.stream().mapToInt(Set::size).sum();
    }

    /** Whether this exact (normalised) domain is an entry; no parent lookup. */
    boolean containsExactly(String normalizedDomain) {
        for (Set<String> set : sets) {
            if (set.contains(normalizedDomain)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the domain or any domain above it is an entry. */
    boolean matches(String domain) {
        for (String candidate : candidates(normalize(domain))) {
            if (containsExactly(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The normalised domain followed by each parent, most specific first,
     * stopping before the top-level domain: {@code a.b.com, b.com}.
     */
    static List<String> candidates(String normalizedDomain) {
        List<String> candidates = new ArrayList<>();
        String domain = normalizedDomain;
        while (domain.indexOf('.') > 0) {
            candidates.add(domain);
            domain = domain.substring(domain.indexOf('.') + 1);
        }
        return candidates;
    }

    static String normalize(String domain) {
        String normalized = domain.strip();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            normalized = IDN.toASCII(normalized, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            // Not a valid IDN; compare it as typed. It can't match a valid entry anyway.
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** Whether the text is a usable list entry: a well-formed domain with at least two labels. */
    static boolean isValidEntry(String text) {
        String domain = normalize(text);
        if (domain.length() > 253) {
            return false;
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }
}
