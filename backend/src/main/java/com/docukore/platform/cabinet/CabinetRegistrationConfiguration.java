package com.docukore.platform.cabinet;

import com.docukore.platform.PlatformProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformProperties.class)
public class CabinetRegistrationConfiguration
{

    @Bean
    CabinetRegistrationRepository cabinetRegistrationRepository(
        @Qualifier("dataSource") DataSource runtimeDataSource,
        @Qualifier("platformTransactionManager") PlatformTransactionManager platformTransactionManager,
        PlatformProperties platform)
    {
        return new CabinetRegistrationRepository(runtimeDataSource, platformTransactionManager, platform.schema());
    }
}
