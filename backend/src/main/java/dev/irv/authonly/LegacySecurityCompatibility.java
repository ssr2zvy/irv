package dev.irv.authonly;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Fail closed outside the supported auth-only runtime until the shared login is integrated.
 * Never allow a future controller to inherit the old blanket permit-all fallback. */
@Configuration(proxyBeanMethods = false)
@Profile("!auth-only")
public class LegacySecurityCompatibility {
  @Bean SecurityFilterChain legacySecurity(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(c -> c.anyRequest().denyAll())
        .requestCache(c -> c.disable()).securityContext(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .build();
  }
}
