package com.docukore.platform.provisioning;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables cabinet provisioning capability configuration.
 * Properties validation is deferred until provisioning is invoked.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CabinetProvisioningProperties.class)
public class CabinetProvisioningConfiguration
{
}
