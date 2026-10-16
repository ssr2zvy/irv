package dev.irv.authonly;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.*;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
@Profile("auth-only")
public class ZitadelSecurity {
  @Bean AuthSettings authSettings(Environment env, ObjectMapper mapper) { return new AuthSettings(env, mapper); }
  @Bean(destroyMethod = "close") InviteLedger inviteLedger(AuthSettings settings) { return new InviteLedger(settings); }
  @Bean EnrollmentProvider enrollmentProvider(AuthSettings settings, ObjectMapper mapper) { return new EnrollmentProvider(settings, mapper); }
  @Bean Enrollment enrollment(AuthSettings settings, CertificateGate gate, InviteLedger ledger, EnrollmentProvider provider, Clock clock) {
    return new Enrollment(settings, gate, ledger, provider, clock);
  }
  @Bean Clock authClock() { return Clock.systemUTC(); }
  @Bean CertificateGate certificateGate(AuthSettings settings) { return new CertificateGate(settings); }
  @Bean BoundSessions boundSessions(AuthSettings settings, CertificateGate gate, Clock clock) { return new BoundSessions(settings, gate, clock); }
  @Bean BoundAuthorizationRequests boundAuthorizationRequests(CertificateGate gate, Clock clock) { return new BoundAuthorizationRequests(gate, clock); }

  @Bean ClientRegistrationRepository clientRegistrationRepository() {
    // Fixed endpoints confirmed from this instance's public OIDC discovery. No startup network request.
    ClientRegistration registration = ClientRegistration.withRegistrationId("zitadel")
        .clientId(AuthSettings.CLIENT).clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri(AuthSettings.ORIGIN + "/login/oauth2/code/zitadel")
        .scope("openid", "profile", "urn:zitadel:iam:org:id:" + AuthSettings.ORG,
            "urn:zitadel:iam:org:project:role:" + AuthSettings.ROLE)
        .authorizationUri(AuthSettings.ISSUER + "/oauth/v2/authorize")
        .tokenUri(AuthSettings.ISSUER + "/oauth/v2/token")
        .jwkSetUri(AuthSettings.ISSUER + "/oauth/v2/keys")
        .issuerUri(AuthSettings.ISSUER).userNameAttributeName("sub").clientName("ZITADEL").build();
    return new InMemoryClientRegistrationRepository(registration);
  }

  @Bean JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
    OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
    factory.setJwsAlgorithmResolver(registration -> SignatureAlgorithm.RS256);
    return factory; // Spring OIDC claim validator + nonce validation retained, never replaced with decode-only.
  }

  @Bean SecurityFilterChain authSecurity(HttpSecurity http, ClientRegistrationRepository clients,
      BoundAuthorizationRequests transactions, BoundSessions sessions, Clock clock) throws Exception {
    var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
    resolver.setAuthorizationRequestCustomizer(builder -> {
      OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
      builder.additionalParameters(params -> { params.put("prompt", "login"); params.put("max_age", 0); });
    });
    HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    contexts.setDisableUrlRewriting(true);
    http.securityContext(c -> c.securityContextRepository(contexts))
        .requestCache(c -> c.requestCache(new NullRequestCache()))
        .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .sessionManagement(c -> c.sessionFixation(f -> f.changeSessionId()))
        .authorizeHttpRequests(c -> c
            .requestMatchers("/auth/signup", "/auth/signup/resume", "/auth/csrf", "/oauth2/authorization/zitadel", "/login/oauth2/code/zitadel").permitAll()
            .requestMatchers("/auth/protected", "/auth/activity", "/auth/logout").authenticated()
            .anyRequest().denyAll())
        .exceptionHandling(c -> c.authenticationEntryPoint((req, res, ex) -> res.setStatus(401))
            .accessDeniedHandler((req, res, ex) -> res.setStatus(403)))
        .logout(c -> c.disable())
        .oauth2Login(c -> c.loginPage("/")
            .authorizationEndpoint(e -> e.authorizationRequestResolver(resolver).authorizationRequestRepository(transactions))
            // No UserInfo merge: admission and method evidence come only from the verified ID token.
            .userInfoEndpoint(e -> e.oidcUserService(user -> new DefaultOidcUser(
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_IRV")), user.getIdToken())))
            .authorizedClientRepository(new OAuth2AuthorizedClientRepository() {
              @Override public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, org.springframework.security.core.Authentication a, HttpServletRequest r) { return null; }
              @Override public void saveAuthorizedClient(OAuth2AuthorizedClient c, org.springframework.security.core.Authentication a, HttpServletRequest r, HttpServletResponse s) {}
              @Override public void removeAuthorizedClient(String id, org.springframework.security.core.Authentication a, HttpServletRequest r, HttpServletResponse s) {}
            })
            .successHandler((request, response, authentication) -> {
              try {
                Object started = request.getAttribute(BoundAuthorizationRequests.STARTED);
                if (!(started instanceof Instant instant)) throw LoginPolicy.failure("login_transaction_missing");
                OidcUser user = (OidcUser) authentication.getPrincipal();
                LoginPolicy.validate(user.getIdToken(), instant, clock.instant());
                sessions.create(request, user.getSubject());
                request.getSession().removeAttribute("AUTH_ERROR");
                response.setStatus(303); response.setHeader("Location", "/");
              } catch (OAuth2AuthenticationException ex) {
                fail(request, response, ex.getError().getErrorCode());
              }
            })
            .failureHandler((request, response, exception) -> fail(request, response, "login_validation_failed")));
    return http.build();
  }

  static void fail(HttpServletRequest request, HttpServletResponse response, String code) {
    SecurityContextHolder.clearContext();
    BoundSessions.clear(request, response);
    // Fixed public error codes only, never provider messages, JWTs, auth codes or exception dumps.
    response.setStatus(303);
    response.setHeader("Location", "/?auth_error=" + code);
  }
}
