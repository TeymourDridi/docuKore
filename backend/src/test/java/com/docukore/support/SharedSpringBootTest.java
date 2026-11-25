package com.docukore.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Spring integration tests use restricted runtime credentials and an invocation-owned isolated platform. */
@SpringBootTest(classes = com.docukore.DocukoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "docukore.deployment.mode=local",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1"
    })
@Import(SharedSpringBootTest.PlatformTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class SharedSpringBootTest
{
    @DynamicPropertySource
    protected static void database(DynamicPropertyRegistry registry)
    {
        registerDatabase(registry);
    }

    /**
     * Registers distinct runtime and migration/provisioning credentials on the exact same external test URL.
     *
     * @param registry the Spring test property registry
     */
    protected static void registerDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", OwnedTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", OwnedTestDatabase::runtimeUsername);
        registry.add("spring.datasource.password", OwnedTestDatabase::runtimePassword);
        registry.add("spring.flyway.url", OwnedTestDatabase::jdbcUrl);
        registry.add("spring.flyway.user", OwnedTestDatabase::username);
        registry.add("spring.flyway.password", OwnedTestDatabase::password);
        registry.add("docukore.provisioning.url", OwnedTestDatabase::jdbcUrl);
        registry.add("docukore.provisioning.username", OwnedTestDatabase::username);
        registry.add("docukore.provisioning.password", OwnedTestDatabase::password);
    }

    /**
     * Registers an explicitly owned class platform without creating an unused default platform.
     *
     * @param registry the Spring test property registry
     * @param platform the class-owned, already migrated platform
     */
    protected static void registerPlatform(DynamicPropertyRegistry registry, TestFixtures.OwnedPlatform platform)
    {
        registry.add("docukore.platform.schema", platform::schema);
        registry.add("spring.datasource.hikari.schema", platform::schema);
        registry.add("spring.flyway.schemas", platform::schema);
        registry.add("spring.flyway.default-schema", platform::schema);
        registry.add("spring.session.jdbc.table-name", () -> platform.schema() + ".spring_session");
    }

    /** Context-local fallback for subclasses that do not supply their own isolated platform. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class PlatformTestConfiguration
    {
        @Bean(destroyMethod = "close")
        DefaultPlatformRegistrar defaultPlatformRegistrar(Environment environment)
        {
            return new DefaultPlatformRegistrar(environment);
        }
    }

    /** Eager property registration before application singletons, with cleanup on context destruction. */
    static final class DefaultPlatformRegistrar implements DynamicPropertyRegistrar, AutoCloseable
    {
        /** Environment used only to detect an explicit subclass platform before allocating the fallback. */
        private final Environment environment;
        /** Context-owned default platform, absent when a subclass already registered its own schema. */
        private TestFixtures.OwnedPlatform platform;

        private DefaultPlatformRegistrar(Environment environment)
        {
            this.environment = environment;
        }

        @Override
        public void accept(DynamicPropertyRegistry registry)
        {
            String explicitSchema = environment.getProperty("docukore.platform.schema");
            if (explicitSchema != null && !"public".equals(explicitSchema))
            {
                OwnedTestDatabase.identifier(explicitSchema);
                return;
            }
            platform = TestFixtures.createPlatform("spring_platform");
            registerPlatform(registry, platform);
        }

        @Override
        public void close()
        {
            if (platform != null)
            {
                platform.close();
            }
        }
    }
}
