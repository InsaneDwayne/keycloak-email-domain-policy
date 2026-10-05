package io.github.insanedwayne.keycloak.emaildomainpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Runs against a Keycloak with the packaged JAR installed, as started by
 * {@code integration-tests/compose.yaml}. That Keycloak reads the disposable
 * domains file {@code $DOMAINS_DIR/disposable.txt} (initially
 * {@code integration-tests/disposable-domains.txt}) and checks it for changes
 * every second.
 *
 * <p>Everything goes through the admin REST API: it validates the user profile
 * the same way registration, the account console and identity provider
 * sign-ins do.
 */
class EmailDomainPolicyIT {

    private static final String REALM = "email-domain-policy-it";
    private static final String USERS = "/realms/" + REALM + "/users";
    private static final String STARTUP_FILE_DOMAIN = "from-startup-file.example";
    private static final AtomicInteger USER_COUNT = new AtomicInteger();

    private static final KeycloakAdmin ADMIN = new KeycloakAdmin(
            env("KEYCLOAK_URL", "http://localhost:8080"),
            env("KEYCLOAK_ADMIN", "admin"),
            env("KEYCLOAK_ADMIN_PASSWORD", "admin"));

    @BeforeAll
    static void createRealm() throws InterruptedException {
        ADMIN.awaitStarted(Duration.ofMinutes(3));
        ADMIN.delete("/realms/" + REALM);
        assertEquals(201, ADMIN.post("/realms", Map.of("realm", REALM, "enabled", true)).status());
    }

    @AfterAll
    static void deleteRealm() {
        ADMIN.delete("/realms/" + REALM);
    }

    @Test
    void validatorIsInstalledWithItsOptions() {
        JsonNode validators = ADMIN.get("/serverinfo").json().path("componentTypes").path("org.keycloak.validate.Validator");
        JsonNode validator = null;
        for (JsonNode candidate : validators) {
            if (EmailDomainPolicyValidator.ID.equals(candidate.path("id").asText())) {
                validator = candidate;
            }
        }
        assertTrue(validator != null, "Validator missing from server info");
        List<String> options = new ArrayList<>();
        validator.path("properties").forEach(property -> options.add(property.path("name").asText()));
        assertEquals(List.of("block-disposable", "allowed-domains", "blocked-domains", "allowed-domains-only",
                "allow-current-email", "error-message"), options);
    }

    @Test
    void invalidConfigIsRejected() {
        assertEquals(400, putPolicy(Map.of("allowed-domains-only", true)).status());
        assertEquals(400, putPolicy(Map.of("blocked-domains", List.of("com"))).status());
    }

    @Test
    void disposableDomainsAreRejected() {
        usePolicy(Map.of());
        assertRejected(createUser("mailinator.com"), "error-email-domain-disposable");
        assertRejected(createUser("x.mailinator.com"), "error-email-domain-disposable");
        assertCreated(createUser("example.com"));
    }

    @Test
    void realmDomainListsApply() {
        usePolicy(Map.of("allowed-domains", List.of("mailinator.com"), "blocked-domains", List.of("blocked.example")));
        assertCreated(createUser("mailinator.com"));
        assertRejected(createUser("mail.blocked.example"), "error-email-domain-blocked");
    }

    @Test
    void allowedDomainsOnly() {
        usePolicy(Map.of("allowed-domains", List.of("company.example"), "allowed-domains-only", true));
        assertCreated(createUser("mail.company.example"));
        assertRejected(createUser("example.com"), "error-email-domain-not-allowed");
    }

    @Test
    void usersCanKeepTheirCurrentEmail() {
        usePolicy(null);
        KeycloakAdmin.Response created = createUser("mailinator.com");
        assertCreated(created);
        String user = USERS + created.location().substring(created.location().lastIndexOf('/'));
        ObjectNode representation = (ObjectNode) ADMIN.get(user).json();

        usePolicy(Map.of());
        representation.put("firstName", "Janet");
        assertEquals(204, ADMIN.put(user, representation).status());

        usePolicy(Map.of("allow-current-email", false));
        representation.put("firstName", "Janine");
        assertRejected(ADMIN.put(user, representation), "error-email-domain-disposable");

        usePolicy(Map.of());
        representation.put("email", uniqueEmail("x.mailinator.com"));
        assertRejected(ADMIN.put(user, representation), "error-email-domain-disposable");
    }

    @Test
    void serverWideDisposableFileIsRead() {
        usePolicy(Map.of());
        assertRejected(createUser(STARTUP_FILE_DOMAIN), "error-email-domain-disposable");
    }

    @Test
    void changedDisposableFileIsReloaded() throws IOException, InterruptedException {
        // A new domain each run, so the test also passes against a file left over from an earlier run.
        String domain = "reloaded-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
        usePolicy(Map.of());
        assertCreated(createUser(domain));

        Path file = Path.of(env("DOMAINS_DIR", "")).resolve("disposable.txt");
        assertTrue(Files.isRegularFile(file), "Set DOMAINS_DIR to the directory of Keycloak's " + file.getFileName());
        String content = Files.readString(file);
        Path next = file.resolveSibling("disposable.txt.next");
        Files.writeString(next, (content.endsWith("\n") ? content : content + "\n") + domain + "\n");
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            KeycloakAdmin.Response response = createUser(domain);
            if (response.status() != 201) {
                assertRejected(response, "error-email-domain-disposable");
                return;
            }
            Thread.sleep(1000);
        }
        fail("Keycloak didn't reload " + file + " within 30 seconds");
    }

    /** Sets the validator on the email attribute; {@code null} removes it. */
    private static void usePolicy(Map<String, Object> config) {
        KeycloakAdmin.Response response = putPolicy(config);
        assertEquals(200, response.status(), response.body());
    }

    private static KeycloakAdmin.Response putPolicy(Map<String, Object> config) {
        ObjectNode profile = (ObjectNode) ADMIN.get(USERS + "/profile").json();
        for (JsonNode attribute : profile.path("attributes")) {
            if ("email".equals(attribute.path("name").asText())) {
                ObjectNode validations = attribute.has("validations")
                        ? (ObjectNode) attribute.get("validations")
                        : ((ObjectNode) attribute).putObject("validations");
                if (config == null) {
                    validations.remove(EmailDomainPolicyValidator.ID);
                } else {
                    validations.set(EmailDomainPolicyValidator.ID, KeycloakAdmin.JSON.valueToTree(config));
                }
            }
        }
        return ADMIN.put(USERS + "/profile", profile);
    }

    private static KeycloakAdmin.Response createUser(String domain) {
        String email = uniqueEmail(domain);
        return ADMIN.post(USERS, Map.of("username", email, "email", email,
                "firstName", "Jane", "lastName", "Doe", "enabled", true));
    }

    private static String uniqueEmail(String domain) {
        return "jane-" + USER_COUNT.incrementAndGet() + "@" + domain;
    }

    private static void assertCreated(KeycloakAdmin.Response response) {
        assertEquals(201, response.status(), response.body());
    }

    private static void assertRejected(KeycloakAdmin.Response response, String messageKey) {
        assertEquals(400, response.status(), response.body());
        assertTrue(Objects.requireNonNullElse(response.body(), "").contains(messageKey), response.body());
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
