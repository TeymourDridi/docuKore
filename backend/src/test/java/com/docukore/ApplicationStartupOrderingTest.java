package com.docukore;

import com.docukore.infrastructure.configuration.ApplicationEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Spring lifecycle ordering without bringing up a datasource or PostgreSQL. */
class ApplicationStartupOrderingTest
{
    @Test
    void completesInitializerBeforeSpringEnvironmentAndAnyBeanCreation()
    {
        List<String> events = new ArrayList<>();
        SpringApplication application = new SpringApplication(LifecycleConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
        {
            assertEquals(List.of("initialized"), events);
            events.add("environment");
        });
        try (var context = DocukoreApplication.start(new String[0],
            ApplicationEnvironment.resolve(Map.of(), new String[0], null, null),
            values -> events.add("initialized"), application))
        {
            assertTrue(context.containsBean("lifecycleMarker"));
            assertEquals(List.of("initialized", "environment"), events);
        }
    }

    @Test
    void initializationFailurePreventsSpringStartupEntirely()
    {
        List<String> events = new ArrayList<>();
        SpringApplication application = new SpringApplication(LifecycleConfiguration.class);
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
            events.add("spring"));
        assertThrows(IllegalStateException.class, () -> DocukoreApplication.start(new String[0],
            ApplicationEnvironment.resolve(Map.of(), new String[0], null, null), values ->
            {
                throw new IllegalStateException("fictional failure");
            }, application));
        assertTrue(events.isEmpty());
    }

    /** Minimal lifecycle configuration deliberately excludes database auto-configuration. */
    @Configuration(proxyBeanMethods = false)
    static class LifecycleConfiguration
    {
        @Bean
        String lifecycleMarker()
        {
            return "created";
        }
    }
}
