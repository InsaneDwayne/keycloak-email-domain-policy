package io.github.insanedwayne.keycloak.emaildomainpolicy;

import static io.github.insanedwayne.keycloak.emaildomainpolicy.EmailDomainPolicy.Decision.ACCEPTED;
import static io.github.insanedwayne.keycloak.emaildomainpolicy.EmailDomainPolicy.Decision.BLOCKED;
import static io.github.insanedwayne.keycloak.emaildomainpolicy.EmailDomainPolicy.Decision.DISPOSABLE;
import static io.github.insanedwayne.keycloak.emaildomainpolicy.EmailDomainPolicy.Decision.NOT_ALLOWED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class EmailDomainPolicyTest {

    private static final DomainSet DISPOSABLE_LIST = DomainSet.of(List.of("mailinator.com", "yopmail.fr"));

    @Test
    void acceptsRegularEmail() {
        assertEquals(ACCEPTED, policy(List.of(), List.of(), false).check("jane@example.com"));
    }

    @Test
    void rejectsDisposableDomainAndSubdomain() {
        EmailDomainPolicy policy = policy(List.of(), List.of(), false);
        assertEquals(DISPOSABLE, policy.check("jane@mailinator.com"));
        assertEquals(DISPOSABLE, policy.check("jane@inbox.mailinator.com"));
    }

    @Test
    void acceptsDomainThatOnlyEndsLikeListedDomain() {
        assertEquals(ACCEPTED, policy(List.of(), List.of(), false).check("jane@notmailinator.com"));
    }

    @Test
    void ignoresCaseWhitespaceAndTrailingDot() {
        assertEquals(DISPOSABLE, policy(List.of(), List.of(), false).check("Jane@MailInator.COM."));
    }

    @Test
    void disposableCheckCanBeTurnedOff() {
        EmailDomainPolicy policy = new EmailDomainPolicy(DomainSet.EMPTY, DomainSet.EMPTY, false, DomainSet.EMPTY);
        assertEquals(ACCEPTED, policy.check("jane@mailinator.com"));
    }

    @Test
    void allowedDomainFixesDisposableFalsePositive() {
        assertEquals(ACCEPTED, policy(List.of("yopmail.fr"), List.of(), false).check("jane@yopmail.fr"));
    }

    @Test
    void rejectsBlockedDomainAndSubdomain() {
        EmailDomainPolicy policy = policy(List.of(), List.of("spam-host.example"), false);
        assertEquals(BLOCKED, policy.check("jane@spam-host.example"));
        assertEquals(BLOCKED, policy.check("jane@a.spam-host.example"));
        assertEquals(ACCEPTED, policy.check("jane@example.com"));
    }

    @Test
    void mostSpecificRuleWins() {
        EmailDomainPolicy blockSub = policy(List.of("company.com"), List.of("contractors.company.com"), false);
        assertEquals(ACCEPTED, blockSub.check("jane@company.com"));
        assertEquals(ACCEPTED, blockSub.check("jane@dev.company.com"));
        assertEquals(BLOCKED, blockSub.check("jane@contractors.company.com"));
        assertEquals(BLOCKED, blockSub.check("jane@x.contractors.company.com"));

        EmailDomainPolicy allowSub = policy(List.of("staff.company.com"), List.of("company.com"), false);
        assertEquals(BLOCKED, allowSub.check("jane@company.com"));
        assertEquals(ACCEPTED, allowSub.check("jane@staff.company.com"));
    }

    @Test
    void blockedWinsWhenBothListsHoldTheEntry() {
        assertEquals(BLOCKED, policy(List.of("company.com"), List.of("company.com"), false).check("jane@company.com"));
    }

    @Test
    void allowlistOnlyMode() {
        EmailDomainPolicy policy = policy(List.of("company.com", "company.de"), List.of("contractors.company.com"), true);
        assertEquals(ACCEPTED, policy.check("jane@company.com"));
        assertEquals(ACCEPTED, policy.check("jane@dev.company.de"));
        assertEquals(BLOCKED, policy.check("jane@contractors.company.com"));
        assertEquals(NOT_ALLOWED, policy.check("jane@example.com"));
        assertEquals(NOT_ALLOWED, policy.check("jane@mailinator.com"));
    }

    @Test
    void matchesUnicodeAndPunycodeSpellings() {
        EmailDomainPolicy unicodeEntry = policy(List.of(), List.of("bücher.example"), false);
        assertEquals(BLOCKED, unicodeEntry.check("jane@xn--bcher-kva.example"));
        assertEquals(BLOCKED, unicodeEntry.check("jane@BÜCHER.example"));

        EmailDomainPolicy punycodeEntry = policy(List.of(), List.of("xn--bcher-kva.example"), false);
        assertEquals(BLOCKED, punycodeEntry.check("jane@bücher.example"));
    }

    @Test
    void acceptsValueWithoutDomain() {
        EmailDomainPolicy policy = policy(List.of("company.com"), List.of(), true);
        assertEquals(ACCEPTED, policy.check("jane"));
        assertEquals(ACCEPTED, policy.check("jane@"));
    }

    @Test
    void validatesEntries() {
        assertTrue(DomainSet.isValidEntry("company.com"));
        assertTrue(DomainSet.isValidEntry("Sub.Company.COM."));
        assertTrue(DomainSet.isValidEntry("bücher.example"));
        assertFalse(DomainSet.isValidEntry("com"));
        assertFalse(DomainSet.isValidEntry("*.company.com"));
        assertFalse(DomainSet.isValidEntry("company..com"));
        assertFalse(DomainSet.isValidEntry("-company.com"));
        assertFalse(DomainSet.isValidEntry("jane@company.com"));
        assertFalse(DomainSet.isValidEntry(""));
    }

    @Test
    void readsListSkippingCommentsAndBlankLines() throws IOException {
        DomainSet domains = read("# comment\n\nMailinator.com\n  yopmail.fr  \n");
        assertEquals(2, domains.size());
        assertTrue(domains.matches("mailinator.com"));
        assertTrue(domains.matches("x.yopmail.fr"));
    }

    @Test
    void rejectsListWithInvalidEntry() {
        assertThrows(IOException.class, () -> read("mailinator.com\nnot a domain\n"));
    }

    @Test
    void combinesSets() {
        DomainSet combined = DomainSet.of(List.of("a.example")).plus(DomainSet.of(List.of("b.example")));
        assertTrue(combined.matches("a.example"));
        assertTrue(combined.matches("x.b.example"));
        assertFalse(combined.matches("c.example"));
        assertEquals(2, combined.size());
    }

    @Test
    void bundledListBlocksKnownProvidersOnly() {
        DomainSet bundled = DisposableEmailDomains.bundled();
        assertTrue(bundled.size() > 1000);
        assertTrue(bundled.matches("mailinator.com"));
        assertTrue(bundled.matches("10minutemail.com"));
        assertFalse(bundled.matches("gmail.com"));
        assertFalse(bundled.matches("proton.me"));
        assertFalse(bundled.matches("outlook.com"));
    }

    private static EmailDomainPolicy policy(List<String> allowed, List<String> blocked, boolean allowedOnly) {
        return new EmailDomainPolicy(DomainSet.of(allowed), DomainSet.of(blocked), allowedOnly, DISPOSABLE_LIST);
    }

    private static DomainSet read(String list) throws IOException {
        return DomainSet.read(new ByteArrayInputStream(list.getBytes(StandardCharsets.UTF_8)));
    }
}
