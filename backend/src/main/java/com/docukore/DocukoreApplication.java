package com.docukore;

import com.docukore.infrastructure.bootstrap.DatabaseBootstrap;
import com.docukore.infrastructure.configuration.ApplicationEnvironment;
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
        try
        {
            ApplicationEnvironment.Prepared prepared = ApplicationEnvironment.load(args);
            if (java.util.Arrays.asList(args).contains("--docukore.initialize-test"))
            {
                DatabaseBootstrap.initializeTest(prepared.values());
                LOG.info("event=TEST_DATABASE_INITIALIZED");
                return;
            }
            start(args, prepared, DatabaseBootstrap::initialize, new SpringApplication(DocukoreApplication.class));
        } catch (RuntimeException | LinkageError failure)
        {
            LOG.error("event=APPLICATION_STARTUP_FAILED Check the installation .env selector and selected settings; "
                + "verify PostgreSQL readiness and current credentials. No passwords are changed automatically.");
            System.exit(1);
        }
    }

    /**
     * Complete initialization before Spring prepares its environment, datasource or Flyway beans.
     *
     * @param args application arguments
     * @param prepared resolved installation configuration
     * @param initializer database initialization operation
     * @param application configured Spring launcher
     * @return started application context
     */
    static org.springframework.context.ConfigurableApplicationContext start(String[] args,
        ApplicationEnvironment.Prepared prepared,
        java.util.function.Consumer<java.util.Map<String, String>> initializer,
        SpringApplication application)
    {
        initializer.accept(prepared.values());
        LOG.info("event=APPLICATION_DATABASE_INITIALIZED");
        application.setEnvironment(prepared.environment());
        application.setAddCommandLineProperties(false);
        return application.run(args);
    }
}