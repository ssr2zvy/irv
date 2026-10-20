package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

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
    return registration -> {
      var decoder = factory.createDecoder(registration);
      return token -> {
        try {
          var decoded = decoder.decode(token);
          audit(Stage.ID_TOKEN, Outcome.PASSED, Reason.VALID);
          return decoded;
        } catch (RuntimeException ex) {
          audit(Stage.ID_TOKEN, Outcome.FAILED, Reason.TOKEN_VALIDATION_FAILED);
          throw ex;
        }
      };
    }; // Original Spring signature/claim validators retained. Nonce is validated later by OIDC.

  }

  @Bean org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer auditFirewall() {
    return web -> web.requestRejectedHandler((request, response, ex) -> {
      audit(Stage.ROUTE, Outcome.FAILED, Reason.FIREWALL_REJECTED);
      response.setStatus(400);
    });
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
    var tokenClient = new org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient();
    http.addFilterAfter(new org.springframework.web.filter.OncePerRequestFilter() {
          @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
              jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
            if (!Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(req.getMethod()))
              audit(Stage.CSRF, Outcome.PASSED, Reason.VALID);
            chain.doFilter(req, res);
          }
        }, org.springframework.security.web.csrf.CsrfFilter.class)
        .securityContext(c -> c.securityContextRepository(contexts))
        .requestCache(c -> c.requestCache(new NullRequestCache()))
        .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .sessionManagement(c -> c.sessionFixation(f -> f.changeSessionId()))
        .authorizeHttpRequests(c -> c
            .requestMatchers(org.springframework.http.HttpMethod.GET, "/auth/csrf", "/login/oauth2/code/zitadel").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/auth/signup", "/auth/signup/resume", "/oauth2/authorization/zitadel").permitAll()
            // No protected-path list: every new route inherits the same session binding checks.
            // AuthOnlyConfiguration independently keeps unfinished routes unreachable.
            .anyRequest().access(new BoundSessionAuthorization(sessions)))
        .exceptionHandling(c -> c.authenticationEntryPoint((req, res, ex) -> {
              audit(Stage.AUTHORIZATION, Outcome.FAILED, Reason.MISSING_SESSION); res.setStatus(401);
            }).accessDeniedHandler((req, res, ex) -> {
              if (ex instanceof org.springframework.security.web.csrf.CsrfException)
                audit(Stage.CSRF, Outcome.FAILED, Reason.CSRF_REJECTED);
              else audit(Stage.AUTHORIZATION, Outcome.FAILED, Reason.ACCESS_DENIED);
              res.setStatus(403);
            }))
        .logout(c -> c.disable())
        .oauth2Login(c -> c.loginPage("/")
            .tokenEndpoint(e -> e.accessTokenResponseClient(grant -> {
              audit(Stage.TOKEN_EXCHANGE, Outcome.STARTED, Reason.NONE);
              try {
                var result = tokenClient.getTokenResponse(grant);
                audit(Stage.TOKEN_EXCHANGE, Outcome.PASSED, Reason.VALID);
                return result;
              } catch (RuntimeException ex) {
                audit(Stage.TOKEN_EXCHANGE, Outcome.FAILED, Reason.PROVIDER_OR_RESPONSE_FAILURE);
                throw ex;
              }
            }))
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
                audit(Stage.OIDC, Outcome.PASSED, Reason.VALID);
                Object started = request.getAttribute(BoundAuthorizationRequests.STARTED);
                if (!(started instanceof Instant instant)) throw LoginPolicy.failure("login_transaction_missing");
                OidcUser user = (OidcUser) authentication.getPrincipal();
                LoginPolicy.validate(user.getIdToken(), instant, clock.instant());
                sessions.create(request, user.getSubject());
                audit(Stage.LOGIN, Outcome.COMPLETED, Reason.VALID);
                request.getSession().removeAttribute("AUTH_ERROR");
                response.setStatus(303); response.setHeader("Location", "/");
              } catch (OAuth2AuthenticationException ex) {
                fail(request, response, ex.getError().getErrorCode());
              }
            })
            .failureHandler((request, response, exception) -> {
              audit(Stage.OIDC, Outcome.FAILED, Reason.LOGIN_VALIDATION_FAILED);
              fail(request, response, "login_validation_failed");
            }));
    return http.build();
  }

  static void fail(HttpServletRequest request, HttpServletResponse response, String code) {
    audit(Stage.LOGIN, Outcome.FAILED, Reason.LOGIN_VALIDATION_FAILED);
    SecurityContextHolder.clearContext();
    BoundSessions.clear(request, response);
    // Fixed public error codes only, never provider messages, JWTs, auth codes or exception dumps.
    response.setStatus(303);
    response.setHeader("Location", "/?auth_error=" + code);
  }
}
