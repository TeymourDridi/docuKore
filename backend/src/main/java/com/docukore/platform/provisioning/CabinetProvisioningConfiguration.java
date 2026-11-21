package com.docukore.platform.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Enables cabinet provisioning capability configuration.
 * Properties validation is deferred until provisioning is invoked.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CabinetProvisioningProperties.class)
public class CabinetProvisioningConfiguration
{

    /**
     * Logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CabinetProvisioningConfiguration.class);

    public CabinetProvisioningConfiguration(DataSource dataSource)
    {
        LOG.info("Cabinet provisioning configuration enabled");
    }
}
