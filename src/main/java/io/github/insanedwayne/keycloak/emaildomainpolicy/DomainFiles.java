package io.github.insanedwayne.keycloak.emaildomainpolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain list files from a server option, read strictly at startup and, on
 * request, read again when one of them changed.
 *
 * <p>A reload replaces the domains only if every file reads cleanly; otherwise
 * the previous domains stay in place. Not thread-safe: one caller at a time.
 */
final class DomainFiles {

    static final DomainFiles NONE = new DomainFiles(List.of(), List.of(), DomainSet.EMPTY);

    private final List<Path> paths;
    private List<Stamp> stamps;
    private DomainSet domains;

    private DomainFiles(List<Path> paths, List<Stamp> stamps, DomainSet domains) {
        this.paths = paths;
        this.stamps = stamps;
        this.domains = domains;
    }

    /** Reads the files; a missing file or a malformed line fails, so Keycloak doesn't start. */
    static DomainFiles read(String[] paths) {
        List<Path> files = new ArrayList<>();
        for (String path : paths == null ? new String[0] : paths) {
            if (!path.isBlank()) {
                files.add(Path.of(path.strip()));
            }
        }
        if (files.isEmpty()) {
            return NONE;
        }
        try {
            List<Stamp> stamps = stamps(files);
            return new DomainFiles(List.copyOf(files), stamps, readAll(files, false));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    DomainSet domains() {
        return domains;
    }

    boolean isEmpty() {
        return paths.isEmpty();
    }

    /**
     * Reads the files again if any of them changed since the last read.
     *
     * @return whether the domains were replaced
     * @throws IOException if a file changed but can't be used; the previous
     *         domains stay in place, and the same change isn't reported twice
     */
    boolean reloadIfChanged() throws IOException {
        List<Stamp> current = stamps(paths);
        if (current.equals(stamps)) {
            return false;
        }
        // Recorded first, so a broken version is reported once, not on every check.
        stamps = current;
        domains = readAll(paths, true);
        return true;
    }

    private static DomainSet readAll(List<Path> paths, boolean rejectEmpty) throws IOException {
        DomainSet domains = DomainSet.EMPTY;
        for (Path path : paths) {
            DomainSet file = DomainSet.read(path);
            if (rejectEmpty && file.isEmpty()) {
                // Most likely truncated while being written.
                throw new IOException("Domain list " + path + " has no entries");
            }
            domains = domains.plus(file);
        }
        return domains;
    }

    private static List<Stamp> stamps(List<Path> paths) throws IOException {
        List<Stamp> stamps = new ArrayList<>();
        for (Path path : paths) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                stamps.add(new Stamp(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size()));
            } catch (IOException e) {
                throw new IOException("Could not read domain list " + path + ": " + e.getMessage(), e);
            }
        }
        return stamps;
    }

    /** Identifies a file version; the file key (inode) catches files replaced by a rename. */
    private record Stamp(Object fileKey, FileTime modified, long size) {
    }
}
