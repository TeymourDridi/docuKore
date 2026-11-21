package com.docukore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for Docukore medical practice management platform.
 *
 * <p>This is a modular monolith using Spring Modulith for architectural boundaries.
 * Future business modules will include platform and medical-cabinet domains.
 */
@SpringBootApplication
public class DocukoreApplication
{

    /**
     * Logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DocukoreApplication.class);

    public static void main(String[] args)
    {
        LOG.info("Starting Docukore medical practice management platform");
        SpringApplication.run(DocukoreApplication.class, args);
        LOG.info("Docukore application started successfully");
    }
}