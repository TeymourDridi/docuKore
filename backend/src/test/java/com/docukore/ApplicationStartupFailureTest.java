package com.docukore;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual Java process failures must be actionable without exposing configuration or exception stacks. */
class ApplicationStartupFailureTest
{
    @Test
    void missingFileFailsSafelyBeforeSpringStarts() throws Exception
    {
        verifyFailure(null);
    }

    @Test
    void malformedFileFailsSafelyBeforeSpringStarts() throws Exception
    {
        verifyFailure("fictional-sensitive-configuration\n");
    }

    @Test
    void incompleteBootstrapFailsSafelyBeforeSpringStarts() throws Exception
    {
        verifyFailure("DB_PASSWORD='fictional-sensitive-configuration'\n");
    }

    private void verifyFailure(String content) throws Exception
    {
        Path directory = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "startup-failure-");
        try
        {
            Path file = directory.resolve("fictional-sensitive-path.env");
            if (content != null)
            {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
            String classpath = System.getProperty("java.class.path").replace('\\', '/');
            Path arguments = Files.writeString(directory.resolve("arguments"),
                "-cp\n\"" + classpath + "\"\ncom.docukore.DocukoreApplication\n"
                    + "\"--docukore.env.file=" + file.toString().replace('\\', '/') + "\"\n"
                    + "--spring.profiles.active=local\n", StandardCharsets.UTF_8);
            Path outputFile = directory.resolve("output");
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "@" + arguments);
            builder.redirectErrorStream(true).redirectOutput(outputFile.toFile());
            builder.environment().keySet().removeIf(key -> key.matches(
                "(?i)(DOCUKORE_.*|SPRING_.*|DB_.*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)"));
            Process process = builder.start();
            try
            {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Failed startup must terminate promptly");
                assertEquals(1, process.exitValue());
            } finally
            {
                if (process.isAlive())
                {
                    process.destroyForcibly().waitFor();
                }
            }
            String output = Files.readString(outputFile);
            assertTrue(output.contains("event=APPLICATION_STARTUP_FAILED"));
            assertTrue(output.contains("Check the installation .env selector and selected settings"));
            assertTrue(output.contains("PostgreSQL readiness and current credentials"));
            assertFalse(output.contains("fictional-sensitive"));
            assertFalse(output.contains("Exception"));
            assertFalse(output.contains("\tat "));
            assertFalse(output.contains("Starting DocukoreApplication"));
            assertFalse(output.contains("APPLICATION_DATABASE_INITIALIZED"));
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
