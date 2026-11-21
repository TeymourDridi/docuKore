package com.docukore.identity;

import com.docukore.platform.PlatformProperties;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.session.jdbc.config.annotation.SpringSessionTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformProperties.class)
public class IdentityPersistenceConfiguration
{

    @Bean
    AccountRepository accountRepository(DataSource dataSource, PlatformProperties platform)
    {
        return new AccountRepository(dataSource, platform.schema());
    }

    @Bean
    @SpringSessionTransactionManager
    JdbcTransactionManager platformTransactionManager(DataSource dataSource)
    {
        return new JdbcTransactionManager(dataSource);
    }

    // Adding a JDBC manager disables Boot's default JPA manager; retain tenant JPA explicitly.
    @Bean(name = "transactionManager")
    @Primary
    JpaTransactionManager tenantTransactionManager(EntityManagerFactory entityManagers)
    {
        return new JpaTransactionManager(entityManagers);
    }
}