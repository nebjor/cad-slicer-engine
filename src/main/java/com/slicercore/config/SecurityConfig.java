package com.slicercore.config;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The public API (quotes, orders, payments) stays open; everything under {@code /api/admin}
 * needs the owner's HTTP Basic credentials from the environment. Stateless, no CSRF tokens:
 * the admin page sends the credentials with every request.
 *
 * A failed authentication answers with a plain 401 problem detail and deliberately without a
 * {@code WWW-Authenticate: Basic} challenge, so browsers do not pop their native login dialog
 * over the admin page.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AdminProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String ADMIN_ROLE = "ADMIN";

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthorized = SecurityConfig::writeUnauthorized;
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/admin/**").hasRole(ADMIN_ROLE)
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(unauthorized));
        return http.build();
    }

    @Bean
    public UserDetailsService adminUsers(AdminProperties admin, PasswordEncoder encoder) {
        if (!admin.enabled()) {
            log.warn("ADMIN_PASSWORD is not set: the admin API answers 401 to everyone");
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(admin.username())
                .password(encoder.encode(admin.password()))
                .roles(ADMIN_ROLE)
                .build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void writeUnauthorized(jakarta.servlet.http.HttpServletRequest request,
                                          HttpServletResponse response,
                                          org.springframework.security.core.AuthenticationException exception)
            throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Admin credentials required.\"}");
    }
}
