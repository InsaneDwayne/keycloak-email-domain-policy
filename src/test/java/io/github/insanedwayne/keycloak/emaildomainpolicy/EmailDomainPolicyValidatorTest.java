package io.github.insanedwayne.keycloak.emaildomainpolicy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.Config;
import org.keycloak.models.UserModel;
import org.keycloak.userprofile.AttributeContext;
import org.keycloak.userprofile.UserProfileAttributeValidationContext;
import org.keycloak.userprofile.UserProfileContext;
import org.keycloak.validate.ValidationContext;
import org.keycloak.validate.ValidationError;
import org.keycloak.validate.ValidationResult;
import org.keycloak.validate.ValidatorConfig;

class EmailDomainPolicyValidatorTest {

    private final EmailDomainPolicyValidator validator =
            new EmailDomainPolicyValidator(DomainSet.of(List.of("mailinator.com", "yopmail.fr")));

    @Test
    void emptyConfigBlocksDisposableDomains() {
        assertTrue(validate("jane@example.com", ValidatorConfig.EMPTY).isValid());

        ValidationError error = singleError(validate("jane@mailinator.com", ValidatorConfig.EMPTY));
        assertEquals(EmailDomainPolicyValidator.ID, error.getValidatorId());
        assertEquals("email", error.getInputHint());
        assertEquals("error-email-domain-disposable", error.getMessage());
        assertArrayEquals(new Object[] {"email", "jane@mailinator.com", "mailinator.com"},
                error.getInputHintWithMessageParameters());
    }

    @Test
    void reportsEachDecisionWithItsMessage() {
        ValidatorConfig config = config(Map.of(
                "allowed-domains", List.of("company.com"),
                "blocked-domains", List.of("contractors.company.com"),
                "allowed-domains-only", true));

        assertTrue(validate("jane@company.com", config).isValid());
        assertEquals("error-email-domain-blocked", singleError(validate("jane@contractors.company.com", config)).getMessage());
        assertEquals("error-email-domain-not-allowed", singleError(validate("jane@example.com", config)).getMessage());
    }

    @Test
    void errorMessageReplacesEveryDefaultMessage() {
        ValidatorConfig config = config(Map.of(
                "allowed-domains", List.of("company.com", "mailinator.com"),
                "blocked-domains", List.of("x.company.com"),
                "error-message", "Please sign up with your company email."));

        for (String email : List.of("jane@x.company.com", "jane@yopmail.fr")) {
            assertEquals("Please sign up with your company email.", singleError(validate(email, config)).getMessage());
        }
        ValidatorConfig allowedOnly = config(Map.of(
                "allowed-domains", List.of("company.com"), "allowed-domains-only", true, "error-message", "custom-key"));
        assertEquals("custom-key", singleError(validate("jane@example.com", allowedOnly)).getMessage());
    }

    @Test
    void acceptsOptionsAsAdminConsoleStrings() {
        ValidatorConfig config = config(Map.of(
                "allowed-domains", "company.com##company.de",
                "allowed-domains-only", "true",
                "block-disposable", "false"));

        assertTrue(validate("jane@company.de", config).isValid());
        assertFalse(validate("jane@example.com", config).isValid());
        assertTrue(validate("jane@mailinator.com", config(Map.of("block-disposable", "false"))).isValid());
    }

    @Test
    void skipsEmptyValue() {
        assertTrue(validate("", ValidatorConfig.EMPTY).isValid());
    }

    @Test
    void acceptsUnchangedEmailOfExistingUser() {
        assertTrue(validator.validate("Jane@mailinator.com", "email", profileContext("jane@mailinator.com"),
                ValidatorConfig.EMPTY).isValid());
    }

    @Test
    void currentEmailCheckCanBeTurnedOff() {
        ValidatorConfig config = config(Map.of("allow-current-email", false));
        assertFalse(validator.validate("jane@mailinator.com", "email", profileContext("jane@mailinator.com"),
                config).isValid());
    }

    @Test
    void rejectsExistingUserChangingToDisposableEmail() {
        assertFalse(validator.validate("jane@mailinator.com", "email", profileContext("jane@example.com"),
                ValidatorConfig.EMPTY).isValid());
    }

    @Test
    void rejectsDisposableEmailOfNewUser() {
        assertFalse(validator.validate("jane@mailinator.com", "email", profileContext(null),
                ValidatorConfig.EMPTY).isValid());
    }

    @Test
    void validateConfigAcceptsExamples() {
        assertTrue(validateConfig(Map.of()).isValid());
        assertTrue(validateConfig(Map.of(
                "allowed-domains", List.of("company.com", "company.de"),
                "blocked-domains", List.of("contractors.company.com"),
                "allowed-domains-only", true,
                "error-message", "Please sign up with your company email.")).isValid());
    }

    @Test
    void validateConfigRejectsBadDomains() {
        ValidationResult result = validateConfig(Map.of(
                "allowed-domains", List.of("com", "ok.example"),
                "blocked-domains", List.of("*.example.com")));
        assertEquals(2, result.getErrors().size());
        assertTrue(result.hasErrorsForInputHint("allowed-domains"));
        assertTrue(result.hasErrorsForInputHint("blocked-domains"));
    }

    @Test
    void validateConfigRejectsAllowlistOnlyWithoutAllowedDomains() {
        assertTrue(validateConfig(Map.of("allowed-domains-only", true)).hasErrorsForInputHint("allowed-domains-only"));
    }

    @Test
    void serverFilesExtendTheLists(@TempDir Path dir) throws IOException {
        Path disposable = Files.writeString(dir.resolve("disposable.txt"), "# extra\nextra-temp.example\n");
        Path blocked = Files.writeString(dir.resolve("blocked.txt"), "competitor.example\n");
        Path allowed = Files.writeString(dir.resolve("allowed.txt"), "company.com\n");
        validator.init(scope(Map.of(
                "disposable-domains-file", disposable.toString(),
                "blocked-domains-file", blocked.toString(),
                "allowed-domains-file", allowed.toString())));

        assertEquals("error-email-domain-disposable", singleError(validate("jane@extra-temp.example", ValidatorConfig.EMPTY)).getMessage());
        assertFalse(validate("jane@mailinator.com", ValidatorConfig.EMPTY).isValid());
        assertEquals("error-email-domain-blocked", singleError(validate("jane@competitor.example", ValidatorConfig.EMPTY)).getMessage());

        ValidatorConfig allowedOnly = config(Map.of("allowed-domains-only", true));
        assertTrue(validateConfig(Map.of("allowed-domains-only", true)).isValid());
        assertTrue(validate("jane@company.com", allowedOnly).isValid());
        assertFalse(validate("jane@example.com", allowedOnly).isValid());
    }

    @Test
    void serverFilesAcceptSeveralPaths(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "a.example\n");
        Path b = Files.writeString(dir.resolve("b.txt"), "b.example\n");
        validator.init(scope(Map.of("blocked-domains-file", a + "," + b)));

        assertFalse(validate("jane@a.example", ValidatorConfig.EMPTY).isValid());
        assertFalse(validate("jane@b.example", ValidatorConfig.EMPTY).isValid());
    }

    @Test
    void missingServerFileFailsStartup(@TempDir Path dir) {
        assertThrows(UncheckedIOException.class,
                () -> validator.init(scope(Map.of("blocked-domains-file", dir.resolve("missing.txt").toString()))));
    }

    @Test
    void reloadsChangedDisposableFiles(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("disposable.txt"), "first.example\n");
        validator.init(scope(Map.of("disposable-domains-file", file.toString(),
                "disposable-domains-reload-interval", "60")));
        assertFalse(validate("jane@first.example", ValidatorConfig.EMPTY).isValid());

        Files.writeString(file, "second.example\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        validator.reloadDisposableFiles();
        assertTrue(validate("jane@first.example", ValidatorConfig.EMPTY).isValid());
        assertFalse(validate("jane@second.example", ValidatorConfig.EMPTY).isValid());
        assertFalse(validate("jane@mailinator.com", ValidatorConfig.EMPTY).isValid());

        Files.writeString(file, "broken entry\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
        validator.reloadDisposableFiles();
        assertFalse(validate("jane@second.example", ValidatorConfig.EMPTY).isValid());
    }

    @Test
    void negativeReloadIntervalFailsStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.init(scope(Map.of("disposable-domains-reload-interval", "-1"))));
    }

    /** The locales of Keycloak's login theme, named as Keycloak looks them up in provider JARs. */
    private static final List<String> LOCALES = List.of(
            "ar", "az", "ca", "cs", "da", "de", "el", "en", "es", "eu", "fa", "fi", "fr", "hr", "hu", "hy", "id",
            "it", "ja", "ka", "kk", "ko", "ky", "lt", "lv", "nl", "no", "pl", "pt", "pt_BR", "ro", "ru", "sk",
            "sl", "sv", "th", "tr", "uk", "vi", "zh_CN", "zh_TW");

    @Test
    void everyLocaleHasEveryMessage() throws IOException {
        Set<String> keys = Set.of("error-email-domain-disposable", "error-email-domain-blocked", "error-email-domain-not-allowed");
        for (String locale : LOCALES) {
            Properties messages = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/theme-resources/messages/messages_" + locale + ".properties")) {
                assertNotNull(in, locale);
                messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            assertEquals(keys, messages.stringPropertyNames(), locale);
            for (String key : keys) {
                // Keycloak formats messages with MessageFormat: a single ' would hide the text after it.
                assertFalse(messages.getProperty(key).replace("''", "").contains("'"), locale + " " + key);
            }
        }
    }

    @Test
    void noMessageFilesBesidesTheLocales() throws Exception {
        try (Stream<Path> files = Files.list(Path.of(getClass().getResource("/theme-resources/messages").toURI()))) {
            assertEquals(Set.copyOf(LOCALES), files
                    .map(file -> file.getFileName().toString().replaceAll("^messages_|\\.properties$", ""))
                    .collect(Collectors.toSet()));
        }
    }

    @Test
    void configPropertiesListEveryOption() {
        assertEquals(
                List.of("block-disposable", "allowed-domains", "blocked-domains", "allowed-domains-only",
                        "allow-current-email", "error-message"),
                validator.getConfigProperties().stream().map(p -> p.getName()).toList());
    }

    private ValidationContext validate(String email, ValidatorConfig config) {
        return validator.validate(email, "email", new ValidationContext(), config);
    }

    private ValidationResult validateConfig(Map<String, Object> config) {
        return validator.validateConfig(null, config(config));
    }

    private static ValidationError singleError(ValidationContext context) {
        assertEquals(1, context.getErrors().size(), context.getErrors().toString());
        return context.getErrors().iterator().next();
    }

    private static ValidatorConfig config(Map<String, Object> values) {
        return new ValidatorConfig(values);
    }

    private static ValidationContext profileContext(String currentEmail) {
        UserModel user = currentEmail == null ? null : stub(UserModel.class, Map.of("getEmail", currentEmail));
        AttributeContext attributeContext =
                new AttributeContext(UserProfileContext.UPDATE_PROFILE, null, null, user, null, null);
        return new UserProfileAttributeValidationContext(attributeContext);
    }

    private static Config.Scope scope(Map<String, String> values) {
        return (Config.Scope) Proxy.newProxyInstance(Config.Scope.class.getClassLoader(), new Class<?>[] {Config.Scope.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> values.get((String) args[0]);
                    case "getArray" -> values.containsKey(args[0]) ? values.get(args[0]).split(",") : null;
                    case "getLong" -> values.containsKey(args[0]) ? Long.valueOf(values.get(args[0])) : args[1];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A stub that answers the given no-argument methods and fails on everything else. */
    private static <T> T stub(Class<T> type, Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (answers.containsKey(method.getName())) {
                return answers.get(method.getName());
            }
            throw new UnsupportedOperationException(method.getName());
        }));
    }
}
