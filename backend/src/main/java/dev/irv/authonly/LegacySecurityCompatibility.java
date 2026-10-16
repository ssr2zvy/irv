package dev.irv.authonly;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Adding OAuth libraries must not install a generated-password login over the legacy profiles.
 * Legacy RequestGuard/Auth keep their existing responsibility. This does not enable them in auth-only. */
@Configuration(proxyBeanMethods = false)
@Profile("!auth-only")
public class LegacySecurityCompatibility {
  @Bean SecurityFilterChain legacySecurity(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable()).authorizeHttpRequests(c -> c.anyRequest().permitAll())
        .requestCache(c -> c.disable()).securityContext(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .build();
  }
}
