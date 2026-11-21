package com.docukore;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers correlation outside and before Spring Security so denials also carry a request ID. */
@Configuration(proxyBeanMethods = false)
public class RequestDiagnosticsConfiguration
{
    @Bean
    FilterRegistrationBean<RequestDiagnosticsFilter> requestDiagnosticsFilterRegistration()
    {
        var registration = new FilterRegistrationBean<>(new RequestDiagnosticsFilter());
        registration.setName("requestDiagnosticsFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        return registration;
    }
}
