package io.github.insanedwayne.keycloak.emaildomainpolicy;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.ConfiguredProvider;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.timer.TimerProvider;
import org.keycloak.userprofile.UserProfileAttributeValidationContext;
import org.keycloak.validate.AbstractStringValidator;
import org.keycloak.validate.ValidationContext;
import org.keycloak.validate.ValidationError;
import org.keycloak.validate.ValidationResult;
import org.keycloak.validate.ValidatorConfig;

/**
 * User profile validator {@value #ID}: decides which email domains may be used.
 * Attach it to the {@code email} attribute of the realm's user profile; it then
 * applies to registration, profile updates, identity provider sign-ins and the
 * admin API alike. See {@link EmailDomainPolicy} for the decision order.
 *
 * <p>Per realm, the options below are set in the user profile. Server-wide,
 * operators can extend the lists with files on disk (one domain per line,
 * {@code #} comments), read at startup:
 * <pre>
 * --spi-validator-email-domain-policy-disposable-domains-file=/path/a.txt,/path/b.txt
 * --spi-validator-email-domain-policy-blocked-domains-file=...
 * --spi-validator-email-domain-policy-allowed-domains-file=...
 * </pre>
 * With {@code --spi-validator-email-domain-policy-disposable-domains-reload-interval=<seconds>},
 * the disposable files are also checked for changes at that interval and read
 * again; a change that can't be read keeps the previous list.
 */
public class EmailDomainPolicyValidator extends AbstractStringValidator implements ConfiguredProvider {

    public static final String ID = "email-domain-policy";

    static final String BLOCK_DISPOSABLE = "block-disposable";
    static final String ALLOWED_DOMAINS = "allowed-domains";
    static final String BLOCKED_DOMAINS = "blocked-domains";
    static final String ALLOWED_DOMAINS_ONLY = "allowed-domains-only";
    static final String ALLOW_CURRENT_EMAIL = "allow-current-email";
    static final String ERROR_MESSAGE = "error-message";

    static final String DISPOSABLE_DOMAINS_FILE = "disposable-domains-file";
    static final String BLOCKED_DOMAINS_FILE = "blocked-domains-file";
    static final String ALLOWED_DOMAINS_FILE = "allowed-domains-file";
    static final String DISPOSABLE_DOMAINS_RELOAD_INTERVAL = "disposable-domains-reload-interval";

    static final String MESSAGE_DISPOSABLE = "error-email-domain-disposable";
    static final String MESSAGE_BLOCKED = "error-email-domain-blocked";
    static final String MESSAGE_NOT_ALLOWED = "error-email-domain-not-allowed";

    private static final Logger LOG = System.getLogger(EmailDomainPolicyValidator.class.getName());

    private DomainSet bundled;
    private volatile DomainSet disposable;
    private DomainFiles disposableFiles = DomainFiles.NONE;
    private long reloadIntervalSeconds;
    private DomainSet serverAllowed = DomainSet.EMPTY;
    private DomainSet serverBlocked = DomainSet.EMPTY;

    public EmailDomainPolicyValidator() {
    }

    EmailDomainPolicyValidator(DomainSet bundled) {
        this.bundled = bundled;
        this.disposable = bundled;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public void init(Config.Scope config) {
        disposableFiles = DomainFiles.read(config.getArray(DISPOSABLE_DOMAINS_FILE));
        disposable = bundled().plus(disposableFiles.domains());
        serverAllowed = DomainFiles.read(config.getArray(ALLOWED_DOMAINS_FILE)).domains();
        serverBlocked = DomainFiles.read(config.getArray(BLOCKED_DOMAINS_FILE)).domains();
        reloadIntervalSeconds = config.getLong(DISPOSABLE_DOMAINS_RELOAD_INTERVAL, 0L);
        if (reloadIntervalSeconds < 0) {
            throw new IllegalArgumentException(DISPOSABLE_DOMAINS_RELOAD_INTERVAL + " must not be negative");
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (reloadIntervalSeconds == 0 || disposableFiles.isEmpty()) {
            return;
        }
        // Keycloak's timer runs on every node and is cancelled on shutdown. It
        // isn't ready before startup has finished, hence the event.
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                try (KeycloakSession session = factory.create()) {
                    session.getProvider(TimerProvider.class).schedule(
                            this::reloadDisposableFiles, reloadIntervalSeconds * 1000, ID + "-reload");
                }
            }
        });
    }

    /** Reads the disposable files again if they changed; keeps the current list if they can't be used. */
    void reloadDisposableFiles() {
        try {
            if (disposableFiles.reloadIfChanged()) {
                disposable = bundled().plus(disposableFiles.domains());
                LOG.log(Level.INFO, "Reloaded disposable domain files: {0} entries in the disposable list",
                        disposable.size());
            }
        } catch (IOException | RuntimeException e) {
            // Also catches unexpected errors: one would stop Keycloak's timer thread.
            LOG.log(Level.WARNING, "Keeping the current disposable domain list: {0}", e.getMessage());
        }
    }

    private DomainSet bundled() {
        if (bundled == null) {
            bundled = DisposableEmailDomains.bundled();
        }
        return bundled;
    }

    private DomainSet disposable() {
        if (disposable == null) {
            disposable = bundled();
        }
        return disposable;
    }

    @Override
    protected void doValidate(String value, String inputHint, ValidationContext context, ValidatorConfig config) {
        if (bool(config, ALLOW_CURRENT_EMAIL, true) && isCurrentEmail(value, context)) {
            return;
        }
        String message = switch (policy(config).check(value)) {
            case ACCEPTED -> null;
            case BLOCKED -> MESSAGE_BLOCKED;
            case NOT_ALLOWED -> MESSAGE_NOT_ALLOWED;
            case DISPOSABLE -> MESSAGE_DISPOSABLE;
        };
        if (message != null) {
            String customMessage = config.getString(ERROR_MESSAGE);
            if (customMessage != null && !customMessage.isBlank()) {
                message = customMessage;
            }
            context.addError(new ValidationError(ID, inputHint, message, value, EmailDomainPolicy.domainOf(value)));
        }
    }

    EmailDomainPolicy policy(ValidatorConfig config) {
        return new EmailDomainPolicy(
                serverAllowed.plus(DomainSet.of(domains(config, ALLOWED_DOMAINS))),
                serverBlocked.plus(DomainSet.of(domains(config, BLOCKED_DOMAINS))),
                bool(config, ALLOWED_DOMAINS_ONLY, false),
                bool(config, BLOCK_DISPOSABLE, true) ? disposable() : DomainSet.EMPTY);
    }

    private static boolean isCurrentEmail(String value, ValidationContext context) {
        if (!(context instanceof UserProfileAttributeValidationContext profileContext)) {
            return false;
        }
        UserModel user = profileContext.getAttributeContext().getUser();
        return user != null && value.equalsIgnoreCase(user.getEmail());
    }

    @Override
    public ValidationResult validateConfig(KeycloakSession session, ValidatorConfig config) {
        List<ValidationError> errors = new ArrayList<>();
        for (String key : List.of(ALLOWED_DOMAINS, BLOCKED_DOMAINS)) {
            for (String domain : domains(config, key)) {
                if (!DomainSet.isValidEntry(domain)) {
                    errors.add(new ValidationError(ID, key,
                            "Not a valid domain, or a bare top-level domain: " + domain, domain));
                }
            }
        }
        if (bool(config, ALLOWED_DOMAINS_ONLY, false)
                && domains(config, ALLOWED_DOMAINS).isEmpty() && serverAllowed.isEmpty()) {
            errors.add(new ValidationError(ID, ALLOWED_DOMAINS_ONLY,
                    ALLOWED_DOMAINS_ONLY + " needs at least one entry in " + ALLOWED_DOMAINS));
        }
        return errors.isEmpty() ? ValidationResult.OK : ValidationResult.of(errors.toArray(ValidationError[]::new));
    }

    /**
     * A list option. The user profile JSON gives a list; accept a single string
     * too, split on commas, whitespace or {@code ##} (the admin console's separator).
     */
    static List<String> domains(ValidatorConfig config, String key) {
        Object value = config.get(key);
        if (value == null) {
            return List.of();
        }
        Stream<String> items = value instanceof Collection<?> collection
                ? collection.stream().map(String::valueOf)
                : Stream.of(value.toString());
        return items.flatMap(item -> Arrays.stream(item.split("##|[,\\s]+")))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    /** A boolean option, given as a JSON boolean or as the string the admin console stores. */
    static boolean bool(ValidatorConfig config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null || value.toString().isBlank() ? defaultValue : Boolean.parseBoolean(value.toString().strip());
    }

    @Override
    public String getHelpText() {
        return "Decides which email domains may be used: blocks disposable email providers "
                + "and applies the realm's allowed and blocked domains, each covering its subdomains.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property().name(BLOCK_DISPOSABLE).label("Block disposable domains")
                .helpText("Reject domains on the bundled list of disposable (temporary) email providers.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(true).add()
                .property().name(ALLOWED_DOMAINS).label("Allowed domains")
                .helpText("Accept these domains and their subdomains, even if they are on the disposable list.")
                .type(ProviderConfigProperty.MULTIVALUED_STRING_TYPE).add()
                .property().name(BLOCKED_DOMAINS).label("Blocked domains")
                .helpText("Reject these domains and their subdomains. The most specific entry of the allowed "
                        + "and blocked domains wins; on a tie, blocked wins.")
                .type(ProviderConfigProperty.MULTIVALUED_STRING_TYPE).add()
                .property().name(ALLOWED_DOMAINS_ONLY).label("Allowed domains only")
                .helpText("Reject every domain not covered by the allowed domains.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(false).add()
                .property().name(ALLOW_CURRENT_EMAIL).label("Allow current email")
                .helpText("Let a user keep the email they already have, even if the policy would now reject it.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(true).add()
                .property().name(ERROR_MESSAGE).label("Error message")
                .helpText("Message key or text shown instead of the default messages.")
                .type(ProviderConfigProperty.STRING_TYPE).add()
                .build();
    }
}
