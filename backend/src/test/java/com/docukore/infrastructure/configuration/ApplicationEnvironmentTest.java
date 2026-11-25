package com.docukore.infrastructure.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Literal parsing, mode selection and early binding behavior on synthetic configuration only. */
class ApplicationEnvironmentTest
{
    @Test
    void parsesLiteralQuotesBlankCommentsBomAndMetacharacters()
    {
        Map<String, String> values = EnvFile.parse(
            "﻿# comment\n\n KEY = 'a$${user.home}#=\\b' \nEMPTY=\nQ=\" spaced \"\n");
        assertEquals("a$${user.home}#=\\b", values.get("KEY"));
        assertEquals("", values.get("EMPTY"));
        assertEquals(" spaced ", values.get("Q"));
    }

    @Test
    void rejectsMalformedAssignmentsDuplicatesControlsAndUnclosedQuotes()
    {
        for (String input : new String[] {"KEY=x\nkey=y", "export KEY=x", "KEY='x", "BAD", "KEY=x\u0000",
            "IGNORED=\"unclosed", "\tKEY=value", "# comment\t", "KEY=x\u0085"})
        {
            assertThrows(IllegalArgumentException.class, () -> EnvFile.parse(input));
        }
    }

    @Test
    void selectsHostedConnectionOverridesOnlyInHostedMode()
    {
        Map<String, String> values = Map.of("DB_URL", "local", "DOCUKORE_HOSTED_DB_URL", "hosted",
            "SPRING_PROFILES_ACTIVE", "local", "DOCUKORE_BOOTSTRAP_PASSWORD", "private-admin",
            "DOCUKORE_TEST_PASSWORD", "private-test");
        ApplicationEnvironment.Prepared local = ApplicationEnvironment.resolve(values, new String[0], null, null);
        assertEquals("local", local.values().get("DB_URL"));
        ApplicationEnvironment.Prepared hosted = ApplicationEnvironment.resolve(values,
            new String[] {"--spring.profiles.active=hosted"}, null, null);
        assertEquals("hosted", hosted.values().get("DB_URL"));
        assertNull(hosted.environment().getProperty("DOCUKORE_BOOTSTRAP_PASSWORD"));
        assertNull(hosted.environment().getProperty("DOCUKORE_TEST_PASSWORD"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationEnvironment.resolve(values,
            new String[] {"--spring.profiles.active=local,hosted"}, null, null));
    }

    @Test
    void composeTransportOverridesHostedUrlsWithoutDuplicatingCredentials()
    {
        Map<String, String> values = Map.of("DB_URL", "host-url", "DB_PASSWORD", "fictional",
            "DOCUKORE_COMPOSE_DB_URL", "compose-url");
        ApplicationEnvironment.Prepared host = ApplicationEnvironment.resolve(values,
            new String[] {"--spring.profiles.active=hosted"}, null, null);
        assertEquals("host-url", host.values().get("DB_URL"));
        ApplicationEnvironment.Prepared compose = ApplicationEnvironment.resolve(values,
            new String[] {"--spring.profiles.active=hosted", "--docukore.env.context=compose"}, null, null);
        assertEquals("compose-url", compose.values().get("DB_URL"));
        assertEquals("fictional", compose.values().get("DB_PASSWORD"));
        assertThrows(IllegalArgumentException.class, () -> ApplicationEnvironment.resolve(values,
            new String[] {"--spring.profiles.active=local", "--docukore.env.context=compose"}, null, null));
    }

    @Test
    void bindsCredentialsLiterallyEvenWhenSpringCouldResolveThePlaceholder()
    {
        String literal = "fictional${user.home}\\#=$\\${user.home}\\\\${user.home}";
        ApplicationEnvironment.Prepared prepared = ApplicationEnvironment.resolve(Map.of("DB_PASSWORD", literal),
            new String[0], null, null);
        prepared.environment().getPropertySources().addLast(new org.springframework.core.env.MapPropertySource(
            "synthetic", Map.of("user.home", "must-not-interpolate")));
        assertEquals(literal, prepared.environment().getProperty("spring.datasource.password"));
        assertEquals(literal,
            Binder.get(prepared.environment()).bind("spring.datasource.password", String.class).get());
    }

    @Test
    void explicitAbsoluteInstalledFileDoesNotDependOnWorkingDirectory() throws Exception
    {
        Path directory = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "env-location-");
        try
        {
            Path file = Files.writeString(directory.resolve(".env"), "KEY=value\n");
            assertEquals(file, ApplicationEnvironment.locate(file.toString(), directory.resolve("unrelated"), null));
            assertThrows(IllegalArgumentException.class,
                () -> ApplicationEnvironment.locate(directory.resolve("missing").toString(), directory, null));
            assertThrows(IllegalArgumentException.class,
                () -> ApplicationEnvironment.locate(".env", directory, null));
            assertEquals("value", EnvFile.read(file).get("KEY"));
        } finally
        {
            try (var paths = Files.walk(directory))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                {
                    Files.delete(path);
                }
            }
        }
    }
}
