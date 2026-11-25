package com.example.order.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

@Configuration
class SecurityConfiguration {

    @Bean
    SecurityFilterChain security(HttpSecurity http) {
        return http
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products").authenticated()
                        .requestMatchers("/api/orders", "/api/orders/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The API accepts JSON with a custom idempotency header and exposes no browser forms.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    UserDetailsService users(
            @Value("${checkout.auth.alice-username}") String aliceName,
            @Value("${checkout.auth.alice-password}") String alicePassword,
            @Value("${checkout.auth.bob-username}") String bobName,
            @Value("${checkout.auth.bob-password}") String bobPassword) {
        Assert.isTrue(validUsername(aliceName) && validUsername(bobName),
                "Checkout usernames must be nonblank, at most 255 characters and contain no colon");
        Assert.isTrue(!aliceName.equalsIgnoreCase(bobName), "Checkout usernames must be distinct");
        Assert.hasText(alicePassword, "The alice password must not be blank");
        Assert.hasText(bobPassword, "The bob password must not be blank");
        var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername(aliceName).password(passwordEncoder.encode(alicePassword)).roles("CUSTOMER").build(),
                User.withUsername(bobName).password(passwordEncoder.encode(bobPassword)).roles("CUSTOMER").build()
        );
    }

    private static boolean validUsername(String name) {
        return !name.isBlank() && name.length() <= 255 && name.indexOf(':') == -1;
    }
}
