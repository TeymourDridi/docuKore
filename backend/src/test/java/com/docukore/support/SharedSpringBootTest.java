package com.docukore.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared Spring Boot test base using the existing PostgreSQL instance.
 * Replaces Testcontainers usage in Spring Boot integration tests.
 * Uses the public platform schema (not isolated) since Spring Boot loads the full application context.
 */
@SpringBootTest(classes = com.docukore.DocukoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "docukore.deployment.mode=local",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.schema=public"
    })
// Testcontainers removed - using shared PostgreSQL instance
public abstract class SharedSpringBootTest
{

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> SharedPostgresTest.SHARED_JDBC_URL);
        registry.add("spring.datasource.username", () -> SharedPostgresTest.SHARED_USER);
        registry.add("spring.datasource.password", () -> SharedPostgresTest.SHARED_PASSWORD);
        registry.add("spring.flyway.url", () -> SharedPostgresTest.SHARED_JDBC_URL);
        registry.add("spring.flyway.user", () -> SharedPostgresTest.SHARED_USER);
        registry.add("spring.flyway.password", () -> SharedPostgresTest.SHARED_PASSWORD);
    }
}