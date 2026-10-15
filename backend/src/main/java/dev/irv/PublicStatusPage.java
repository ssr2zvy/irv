package dev.irv;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Public connectivity page on a separate listener, isolated from application routes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "irv.public-status.enabled", havingValue = "true")
public class PublicStatusPage {
  static final int PORT = 8081;
  private static final byte[] PAGE =
      "<!doctype html><html lang=\"en\"><title>irv</title><body>connected successfully</body></html>"
          .getBytes(StandardCharsets.UTF_8);

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> publicStatusConnector() {
    return factory -> {
      Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
      connector.setPort(PORT);
      // Only the colocated connector should reach this temporary page.
      connector.setProperty("address", "127.0.0.1");
      factory.addAdditionalTomcatConnectors(connector);
    };
  }

  @Bean
  FilterRegistrationBean<jakarta.servlet.Filter> publicStatusOnly() {
    FilterRegistrationBean<jakarta.servlet.Filter> registration = new FilterRegistrationBean<>();
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    registration.setFilter(
        (request, response, chain) -> {
          HttpServletRequest req = (HttpServletRequest) request;
          HttpServletResponse res = (HttpServletResponse) response;
          // Actual listener port: never trust a client-controlled Host/forwarded header.
          if (req.getLocalPort() != PORT) {
            chain.doFilter(request, response);
            return;
          }
          res.setHeader("Cache-Control", "no-store");
          res.setHeader("X-Content-Type-Options", "nosniff");
          res.setHeader(
              "Content-Security-Policy",
              "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
          if (!"/".equals(req.getRequestURI())) {
            res.setStatus(404);
          } else if (!"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod())) {
            res.setHeader("Allow", "GET, HEAD");
            res.setStatus(405);
          } else {
            res.setContentType("text/html;charset=UTF-8");
            res.setContentLength(PAGE.length);
            if ("GET".equals(req.getMethod())) {
              res.getOutputStream().write(PAGE);
            }
          }
          // Never dispatch public requests (including errors) into application routes.
        });
    return registration;
  }
}
