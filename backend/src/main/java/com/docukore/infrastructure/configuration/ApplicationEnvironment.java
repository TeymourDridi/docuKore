package com.docukore.infrastructure.configuration;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Resolve the installation file and mode before PostgreSQL or Spring establishes any connection. */
public final class ApplicationEnvironment
{
    /** Connections eligible for same-file hosted overrides. */
    private static final Set<String> CONNECTIONS = Set.of("DB_URL", "DB_USERNAME", "DB_PASSWORD",
        "DOCUKORE_MIGRATION_URL", "DOCUKORE_MIGRATION_USERNAME", "DOCUKORE_MIGRATION_PASSWORD",
        "DOCUKORE_PROVISIONING_URL", "DOCUKORE_PROVISIONING_USERNAME", "DOCUKORE_PROVISIONING_PASSWORD",
        "DOCUKORE_BOOTSTRAP_URL", "DOCUKORE_BOOTSTRAP_USERNAME", "DOCUKORE_BOOTSTRAP_PASSWORD");

    private ApplicationEnvironment()
    {
    }

    public static Prepared load(String[] args)
    {
        String location = argument(args, "docukore.env.file");
        if (location == null)
        {
            location = System.getProperty("docukore.env.file", System.getenv("DOCUKORE_ENV_FILE"));
        }
        Path file = locate(location, Path.of("").toAbsolutePath(), System.getenv("ProgramData"));
        return resolve(EnvFile.read(file), args, System.getProperty("spring.profiles.active",
            System.getenv("SPRING_PROFILES_ACTIVE")), System.getenv("DOCUKORE_DEPLOYMENT_MODE"));
    }

    public static Path locate(String explicit, Path directory, String programData)
    {
        if (explicit != null && !explicit.isBlank())
        {
            Path file = Path.of(explicit);
            if (!file.isAbsolute() || !Files.isRegularFile(file))
            {
                throw EnvFile.failure("DOCUKORE_ENV_FILE must name an existing absolute .env file.");
            }
            return file;
        }
        Path project = discover(directory);
        if (project == null)
        {
            try
            {
                Path code = Path.of(ApplicationEnvironment.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
                project = discover(Files.isDirectory(code) ? code : code.getParent());
            } catch (Exception ignored)
            {
                // Installed launchers need not have a filesystem-backed class location.
            }
        }
        if (project != null)
        {
            return project;
        }
        if (programData != null && !programData.isBlank())
        {
            Path installed = Path.of(programData, "Docukore", ".env");
            if (Files.isRegularFile(installed))
            {
                return installed;
            }
        }
        throw EnvFile.failure("No installation .env found; set DOCUKORE_ENV_FILE to its absolute path.");
    }

    private static Path discover(Path directory)
    {
        for (Path current = directory; current != null; current = current.getParent())
        {
            if (Files.isRegularFile(current.resolve("CLAUDE.md")) && Files.isDirectory(current.resolve("backend")))
            {
                return current.resolve(".env");
            }
        }
        return null;
    }

    public static Prepared resolve(Map<String, String> input, String[] args, String profile, String mode)
    {
        String requested = argument(args, "spring.profiles.active");
        if (requested == null)
        {
            requested = profile;
        }
        if (requested == null || requested.isBlank())
        {
            requested = mode;
        }
        if (requested == null || requested.isBlank())
        {
            requested = input.getOrDefault("SPRING_PROFILES_ACTIVE",
                input.getOrDefault("DOCUKORE_DEPLOYMENT_MODE", "local"));
        }
        String selected = requested.toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("local", "hosted").contains(selected))
        {
            throw EnvFile.failure("Select exactly one application profile: local or hosted.");
        }
        Map<String, String> values = new LinkedHashMap<>(input);
        if (selected.equals("hosted"))
        {
            for (String key : CONNECTIONS)
            {
                String override = values.get("DOCUKORE_HOSTED_" + key);
                if (override != null && !override.isBlank())
                {
                    values.put(key, override);
                }
            }
        }
        String context = argument(args, "docukore.env.context");
        if (context != null)
        {
            if (!"compose".equals(context) || !"hosted".equals(selected))
            {
                throw EnvFile.failure("Compose configuration context requires the hosted profile.");
            }
            for (String key : CONNECTIONS)
            {
                String override = values.get("DOCUKORE_COMPOSE_" + key);
                if (key.endsWith("_URL") && override != null && !override.isBlank())
                {
                    values.put(key, override);
                }
            }
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        bind(properties, values, "DB_URL", "spring.datasource.url");
        bind(properties, values, "DB_USERNAME", "spring.datasource.username");
        bind(properties, values, "DB_PASSWORD", "spring.datasource.password");
        bind(properties, values, "DOCUKORE_MIGRATION_URL", "spring.flyway.url");
        bind(properties, values, "DOCUKORE_MIGRATION_USERNAME", "spring.flyway.user");
        bind(properties, values, "DOCUKORE_MIGRATION_PASSWORD", "spring.flyway.password");
        for (String suffix : Set.of("url", "username", "password", "lock-timeout", "connect-timeout",
            "statement-timeout", "network-timeout"))
        {
            bind(properties, values, "DOCUKORE_PROVISIONING_" + suffix.toUpperCase(java.util.Locale.ROOT)
                .replace("-", ""), "docukore.provisioning." + suffix);
        }
        bind(properties, values, "DOCUKORE_PLATFORM_SCHEMA", "docukore.platform.schema");
        bind(properties, values, "SERVER_PORT", "server.port");
        bind(properties, values, "SERVER_ADDRESS", "server.address");
        bind(properties, values, "SPRING_SESSION_TIMEOUT", "spring.session.timeout");
        if (selected.equals("hosted"))
        {
            bind(properties, values, "DOCUKORE_MAIL_PROVIDER", "docukore.mail.provider");
            for (String suffix : Set.of("client-id", "client-secret", "refresh-token", "sender", "timeout"))
            {
                bind(properties, values, "DOCUKORE_MAIL_GMAIL_" + suffix.toUpperCase(java.util.Locale.ROOT)
                    .replace('-', '_'), "docukore.mail.gmail." + suffix);
            }
            for (String suffix : Set.of("apikey", "sender", "timeout"))
            {
                bind(properties, values, "DOCUKORE_MAIL_RESEND_" + suffix.toUpperCase(java.util.Locale.ROOT),
                    "docukore.mail.resend." + (suffix.equals("apikey") ? "api-key" : suffix));
            }
            for (String suffix : Set.of("app-base-uri", "verification-ttl", "resend-cooldown"))
            {
                bind(properties, values, "DOCUKORE_SIGNUP_" + suffix.toUpperCase(java.util.Locale.ROOT)
                    .replace("-", ""), "docukore.signup." + suffix);
            }
        }
        properties.put("docukore.deployment.mode", selected);
        properties.put("spring.profiles.active", selected);
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("installation", properties)
        {
            @Override
            public String toString()
            {
                return "installation[redacted]";
            }
        });
        environment.setActiveProfiles(selected);
        return new Prepared(Map.copyOf(values), environment);
    }

    private static void bind(Map<String, Object> properties, Map<String, String> values, String key, String property)
    {
        if (values.containsKey(key) && !values.get(key).isBlank())
        {
            properties.put(property, values.get(key).replace("${", "\\${"));
        }
    }

    private static String argument(String[] args, String name)
    {
        String prefix = "--" + name + "=";
        String result = null;
        for (String value : args)
        {
            if (value.startsWith(prefix))
            {
                if (result != null)
                {
                    throw EnvFile.failure("Duplicate startup configuration selector.");
                }
                result = value.substring(prefix.length());
            }
        }
        return result;
    }

    /** Prepared startup state; no generated representation may expose configuration. */
    public static final class Prepared
    {
        /** Selected bootstrap values. */
        private final Map<String, String> values;
        /** Isolated Spring configuration without administrator or test aliases. */
        private final ConfigurableEnvironment environment;

        private Prepared(Map<String, String> values, ConfigurableEnvironment environment)
        {
            this.values = values;
            this.environment = environment;
        }

        public Map<String, String> values()
        {
            return values;
        }

        public ConfigurableEnvironment environment()
        {
            return environment;
        }
    }
}
