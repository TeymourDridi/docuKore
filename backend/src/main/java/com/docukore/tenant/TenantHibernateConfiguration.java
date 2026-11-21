package com.docukore.tenant;

import com.docukore.platform.PlatformProperties;
import org.hibernate.cfg.MultiTenancySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class TenantHibernateConfiguration
{

    private static final Logger log = LoggerFactory.getLogger(TenantHibernateConfiguration.class);

    @Bean
    CabinetTenantIdentifierResolver tenantIdentifierResolver()
    {
        log.debug("Creating tenant identifier resolver for Hibernate multi-tenancy");
        return new CabinetTenantIdentifierResolver();
    }

    @Bean
    CabinetTenantSchemaMapper tenantSchemaMapper(DataSource dataSource, PlatformProperties platform)
    {
        log.debug("Creating tenant schema mapper - platform schema: {}",
            platform.schema());
        return new CabinetTenantSchemaMapper(dataSource, platform.schema());
    }

    @Bean
    HibernatePropertiesCustomizer tenantHibernateProperties(
            CabinetTenantIdentifierResolver resolver,
            CabinetTenantSchemaMapper mapper)
    {
        log.debug("Creating Hibernate properties customizer with tenant overrides");
        return properties ->
        {
            properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
            properties.put(MultiTenancySettings.MULTI_TENANT_SCHEMA_MAPPER, mapper);
            properties.put("hibernate.integrator_provider", (org.hibernate.jpa.boot.spi.IntegratorProvider)
                () ->
                {
                    log.debug("Registering TenantPersistenceGuard integrator");
                    return java.util.List.of(new TenantPersistenceGuard());
                });
            properties.put(
                org.hibernate.cfg.SessionEventSettings.AUTO_SESSION_EVENTS_LISTENER,
                TenantPersistenceGuard.Statements.class.getName());
        };
    }
}