package com.docukore.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.time.Clock;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeploymentProperties.class)
public class AuthenticationConfiguration
{

    @Bean
    Clock applicationClock()
    {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder()
    {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    UserDetailsService userDetailsService(AccountRepository accounts, DeploymentProperties deployment)
    {
        return username ->
        {
            var account = accounts.find(username)
                    .orElseThrow(() -> new UsernameNotFoundException("Authentication failed"));
            return User.withUsername(account.username())
                    .password(account.passwordHash())
                    .authorities("ACCOUNT")
                    .disabled(!account.loginAllowed(deployment))
                    .build();
        };
    }

    @Bean
    DefaultCookieSerializer sessionCookieSerializer(DeploymentProperties deployment)
    {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("SESSION");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(!deployment.local());
        cookie.setSameSite("Lax");
        return cookie;
    }

    @Bean
    SecurityFilterChain authenticationSecurity(HttpSecurity http, UserDetailsService users,
            PasswordEncoder passwords, AccountRepository accounts,
            DeploymentProperties deployment) throws Exception
    {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(!deployment.local()));
        http.authenticationProvider(provider)
            .authorizeHttpRequests(access -> access
                .requestMatchers(
                    "/api/auth/setup",
                    "/api/auth/signup",
                    "/api/auth/verify-email",
                    "/api/auth/resend-verification",
                    "/api/auth/csrf",
                    "/api/auth/login",
                    "/api/auth/logout",
                    "/actuator/health",
                    "/error")
                .permitAll()
                .anyRequest().authenticated())
            .csrf(config -> config.spa().csrfTokenRepository(csrf))
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) ->
                    AuthenticationErrors.unauthenticated(response))
                .accessDeniedHandler((request, response, error) ->
                    AuthenticationErrors.forbidden(response)))
            .formLogin(login -> login.loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) ->
                {
                    accounts.recordLogin(authentication.getName());
                    response.setStatus(200);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"authenticated\":true}");
                })
                .failureHandler((request, response, error) ->
                    AuthenticationErrors.unauthenticated(response)))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .logoutSuccessHandler((request, response, authentication) ->
                    response.setStatus(204)))
            .addFilterBefore(new AccountSessionFilter(accounts, deployment), AuthorizationFilter.class);
        return http.build();
    }
}