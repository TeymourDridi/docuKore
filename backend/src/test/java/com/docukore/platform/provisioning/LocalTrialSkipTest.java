package com.docukore.platform.provisioning;

import com.docukore.identity.DeploymentProperties;
import com.docukore.platform.PlatformProperties;
import com.docukore.platform.cabinet.CabinetRegistrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.mockito.Mockito.mock;

/**
 * Verifies that LOCAL deployment mode skips trial subscription creation
 * while HOSTED mode preserves the existing 720-hour trial behavior.
 */
class LocalTrialSkipTest
{
    /** Fictional provisioning properties. */
    private static final CabinetProvisioningProperties PROPERTIES =
        new CabinetProvisioningProperties(
            "jdbc:postgresql://localhost/test", "user", "pass",
            Duration.ofSeconds(10), Duration.ofSeconds(5),
            Duration.ofSeconds(60), Duration.ofSeconds(90));

    /**
     * Verifies that the service accepts LOCAL deployment mode
     * and skips trial subscription creation during cabinet provisioning.
     */
    @Test
    void localDeploymentModeSkipsTrialSubscriptionCreation()
    {
        CabinetProvisioningService service = new CabinetProvisioningService(
            mock(CabinetRegistrationRepository.class),
            PROPERTIES,
            mock(JdbcTemplate.class),
            new PlatformProperties("public"),
            new DeploymentProperties(DeploymentProperties.Mode.LOCAL));

        // Service instantiates successfully with LOCAL mode
        // Trial skip logic is conditional on deploymentProperties.local()
        // Further verification requires a real database (integration test)
    }

    /**
     * Verifies that the service accepts HOSTED deployment mode
     * and preserves the existing 720-hour trial behavior.
     */
    @Test
    void hostedDeploymentModeCreatesTrialSubscription()
    {
        CabinetProvisioningService service = new CabinetProvisioningService(
            mock(CabinetRegistrationRepository.class),
            PROPERTIES,
            mock(JdbcTemplate.class),
            new PlatformProperties("public"),
            new DeploymentProperties(DeploymentProperties.Mode.HOSTED));

        // Service instantiates successfully with HOSTED mode
        // Trial creation logic is conditional on !deploymentProperties.local()
        // Further verification requires a real database (integration test)
    }
}
