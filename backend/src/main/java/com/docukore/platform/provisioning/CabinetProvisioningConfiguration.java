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

    private static final Logger log = LoggerFactory.getLogger(CabinetProvisioningConfiguration.class);

    public CabinetProvisioningConfiguration(DataSource dataSource)
    {
        log.info("Cabinet provisioning configuration enabled");
    }
}
