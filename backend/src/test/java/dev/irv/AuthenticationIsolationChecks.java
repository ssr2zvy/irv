package dev.irv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Standalone context check: run with compiled production and test classes on the classpath. */
public final class AuthenticationIsolationChecks {
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(App.class);
    app.setWebApplicationType(WebApplicationType.SERVLET);
    try (var context =
        app.run("--spring.main.banner-mode=off", "--server.port=0")) {
      for (Class<?> type :
          new Class<?>[] {
            AgentApi.class,
            Auth.class,
            AuthApi.class,
            Blobs.class,
            ConfigSync.class,
            CostAccounting.class,
            Crypto.class,
            Db.class,
            Errors.class,
            FixedLifecyclePolicy.class,
            Fly.class,
            Hub.class,
            Maintenance.class,
            Preview.class,
            PublicStatusPage.class,
            RequestGuard.class,
            SandboxApi.class,
            SandboxService.class,
            Sockets.class,
            SyncApi.class,
            javax.sql.DataSource.class
          }) {
        if (!context.getBeansOfType(type).isEmpty())
          throw new AssertionError("Unexpected bean: " + type.getName());
      }
      if (context.getBeansOfType(dev.irv.authonly.ApplicationBoundaryConfiguration.class).size() != 1)
        throw new AssertionError("Mandatory authentication configuration missing");
      System.out.println("PASS: legacy services, old auth and database absent from context");
    }
  }
}
