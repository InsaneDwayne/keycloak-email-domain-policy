package io.github.insanedwayne.keycloak.emaildomainpolicy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainFilesTest {

    @TempDir
    Path dir;

    private int version;

    @Test
    void noPathsMeansNoFiles() {
        assertSame(DomainFiles.NONE, DomainFiles.read(null));
        assertSame(DomainFiles.NONE, DomainFiles.read(new String[] {" "}));
    }

    @Test
    void startupFailsOnBadFile() throws IOException {
        Path bad = write("bad.txt", "not a domain\n");
        assertThrows(UncheckedIOException.class, () -> DomainFiles.read(new String[] {bad.toString()}));
        assertThrows(UncheckedIOException.class,
                () -> DomainFiles.read(new String[] {dir.resolve("missing.txt").toString()}));
    }

    @Test
    void reloadsChangedFile() throws IOException {
        Path file = write("list.txt", "a.example\n");
        DomainFiles files = DomainFiles.read(new String[] {file.toString()});
        assertFalse(files.reloadIfChanged());

        write("list.txt", "b.example\n");
        assertTrue(files.reloadIfChanged());
        assertFalse(files.domains().matches("a.example"));
        assertTrue(files.domains().matches("b.example"));
        assertFalse(files.reloadIfChanged());
    }

    @Test
    void reloadsFileReplacedByRename() throws IOException {
        Path file = write("list.txt", "a.example\n");
        DomainFiles files = DomainFiles.read(new String[] {file.toString()});

        Path next = write("list.txt.new", "b.example\n");
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        assertTrue(files.reloadIfChanged());
        assertTrue(files.domains().matches("b.example"));
    }

    @Test
    void keepsDomainsWhenChangedFileIsInvalid() throws IOException {
        Path file = write("list.txt", "a.example\n");
        DomainFiles files = DomainFiles.read(new String[] {file.toString()});

        write("list.txt", "b.example\nnot a domain\n");
        assertThrows(IOException.class, files::reloadIfChanged);
        assertTrue(files.domains().matches("a.example"));
        assertFalse(files.domains().matches("b.example"));
        // Reported once; the next check waits for the next change.
        assertFalse(files.reloadIfChanged());

        write("list.txt", "b.example\n");
        assertTrue(files.reloadIfChanged());
        assertTrue(files.domains().matches("b.example"));
    }

    @Test
    void keepsDomainsWhenChangedFileIsEmpty() throws IOException {
        Path file = write("list.txt", "a.example\n");
        DomainFiles files = DomainFiles.read(new String[] {file.toString()});

        write("list.txt", "# only a comment\n");
        assertThrows(IOException.class, files::reloadIfChanged);
        assertTrue(files.domains().matches("a.example"));
    }

    @Test
    void keepsDomainsWhenFileIsMissing() throws IOException {
        Path file = write("list.txt", "a.example\n");
        DomainFiles files = DomainFiles.read(new String[] {file.toString()});

        Files.delete(file);
        assertThrows(IOException.class, files::reloadIfChanged);
        assertTrue(files.domains().matches("a.example"));
    }

    @Test
    void keepsAllDomainsWhenOneOfSeveralFilesIsInvalid() throws IOException {
        Path a = write("a.txt", "a.example\n");
        Path b = write("b.txt", "b.example\n");
        DomainFiles files = DomainFiles.read(new String[] {a.toString(), b.toString()});

        write("a.txt", "c.example\n");
        write("b.txt", "bad entry\n");
        assertThrows(IOException.class, files::reloadIfChanged);
        assertTrue(files.domains().matches("a.example"));
        assertFalse(files.domains().matches("c.example"));
    }

    /** Writes the file with a new modification time, so changes are seen even within one clock tick. */
    private Path write(String name, String content) throws IOException {
        Path file = Files.writeString(dir.resolve(name), content);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(++version)));
        return file;
    }
}
