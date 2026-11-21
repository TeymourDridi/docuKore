package com.docukore.tenant;

import com.docukore.platform.PlatformProperties;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class TenantHibernateConfiguration
{

    @Bean
    CabinetTenantIdentifierResolver tenantIdentifierResolver()
    {
        return new CabinetTenantIdentifierResolver();
    }

    @Bean
    CabinetTenantSchemaMapper tenantSchemaMapper(DataSource dataSource, PlatformProperties platform)
    {
        return new CabinetTenantSchemaMapper(dataSource, platform.schema());
    }

    @Bean
    HibernatePropertiesCustomizer tenantHibernateProperties(
            CabinetTenantIdentifierResolver resolver,
            CabinetTenantSchemaMapper mapper)
    {
        return properties ->
        {
            properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
            properties.put(MultiTenancySettings.MULTI_TENANT_SCHEMA_MAPPER, mapper);
            properties.put("hibernate.integrator_provider", (org.hibernate.jpa.boot.spi.IntegratorProvider)
                () ->
                {
                    return java.util.List.of(new TenantPersistenceGuard());
                });
            properties.put(
                org.hibernate.cfg.SessionEventSettings.AUTO_SESSION_EVENTS_LISTENER,
                TenantPersistenceGuard.Statements.class.getName());
        };
    }
}