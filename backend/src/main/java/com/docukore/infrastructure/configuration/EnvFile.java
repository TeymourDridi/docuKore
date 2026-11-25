package com.docukore.infrastructure.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared literal UTF-8 installation configuration; no interpolation or escape expansion. */
public final class EnvFile
{
    /** Assignment grammar deliberately excludes shell statements. */
    private static final Pattern ASSIGNMENT = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=(.*)");

    private EnvFile()
    {
    }

    public static Map<String, String> read(Path path)
    {
        try
        {
            if (!Files.isRegularFile(path) || Files.size(path) > 262144)
            {
                throw failure("Missing or oversized .env; set DOCUKORE_ENV_FILE to its absolute path.");
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception)
        {
            throw failure("Cannot read UTF-8 .env; check its location and file permissions.");
        }
    }

    public static Map<String, String> parse(String text)
    {
        Map<String, String> values = new LinkedHashMap<>();
        String content = text.startsWith("﻿") ? text.substring(1) : text;
        int number = 0;
        for (String line : content.split("\\r\\n|\\n|\\r", -1))
        {
            number++;
            if (line.chars().anyMatch(Character::isISOControl))
            {
                throw failure("Invalid .env control character at line " + number + ".");
            }
            if (line.isBlank() || line.stripLeading().startsWith("#"))
            {
                continue;
            }
            Matcher assignment = ASSIGNMENT.matcher(line);
            if (!assignment.matches())
            {
                throw failure("Invalid .env assignment at line " + number + "; expected KEY=VALUE.");
            }
            String key = assignment.group(1);
            String normalized = key.toUpperCase(Locale.ROOT);
            if (values.keySet().stream().anyMatch(existing -> existing.toUpperCase(Locale.ROOT).equals(normalized)))
            {
                throw failure("Duplicate .env key at line " + number + ".");
            }
            String value = assignment.group(2).strip();
            if (value.startsWith("\"") || value.startsWith("'"))
            {
                String quote = value.substring(0, 1);
                if (value.length() < 2 || !value.endsWith(quote))
                {
                    throw failure("Unclosed .env quote at line " + number + ".");
                }
                value = value.substring(1, value.length() - 1);
            }
            if (value.chars().anyMatch(Character::isISOControl))
            {
                throw failure("Invalid .env value at line " + number + ".");
            }
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    /**
     * Only source-controlled text and line numbers may cross a configuration failure boundary.
     * @param message safe diagnostic text
     * @return configuration failure
     */
    static IllegalArgumentException failure(String message)
    {
        return new IllegalArgumentException(message);
    }
}
