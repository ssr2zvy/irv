package dev.irv.authonly;

import dev.irv.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class MandatoryAuthenticationTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "production", "dev", "auth-only", "production,auth-only"})
  void authenticationAndFeatureIsolationDoNotDependOnProfiles(String profiles) {
    new WebApplicationContextRunner()
      .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
      .withUserConfiguration(App.class)
      .withPropertyValues("spring.profiles.active=" + profiles,
          "IRV_AUTH_INGRESS_SECRET=" + "a".repeat(64), "IRV_TUNNEL_ENABLED=false")
      .run(context -> {
        assertNull(context.getStartupFailure());
        assertEquals(1, context.getBeansOfType(ZitadelSecurity.class).size());
        assertEquals(1, context.getBeansOfType(ApplicationBoundaryConfiguration.class).size());
        for (Class<?> type : new Class<?>[] {Auth.class, AuthApi.class, SandboxApi.class,
            SandboxService.class, Sockets.class, Preview.class, AgentApi.class, Maintenance.class,
            javax.sql.DataSource.class}) assertTrue(context.getBeansOfType(type).isEmpty(), type.getName());
        var boundary = (FilterRegistrationBean<?>) context.getBean("applicationBoundary");
        var mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(boundary.getFilter(), context.getBean(FilterChainProxy.class)).build();
        for (String path : new String[] {"/", "/auth/diagnostics", "/auth/status", "/auth/app.js", "/auth/style.css"}) {
          assertEquals(403, mvc.perform(get(path).with(req -> {req.setLocalPort(8081); return req;}))
              .andReturn().getResponse().getStatus(), path);
          var request = get(path).header("X-Irv-Ingress-Key", "a".repeat(64))
              .header("X-Irv-Cert-Verified", "true").header("X-Irv-Cert-Revoked", "false")
              .header("X-Irv-Cert-SHA256", "b".repeat(64))
              .with(req -> {req.setLocalPort(8081); req.setRemoteAddr("127.0.0.1"); return req;});
          assertEquals(200, mvc.perform(request).andReturn().getResponse().getStatus(), path);
        }
        for (String path : new String[] {"/api/sandboxes", "/api/login", "/ws", "/preview", "/future/operation"}) {
          assertEquals(404, mvc.perform(get(path).with(req -> {req.setLocalPort(8081); return req;}))
              .andReturn().getResponse().getStatus(), path);
        }
      });
  }
}
