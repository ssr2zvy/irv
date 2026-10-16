package dev.irv.authonly;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class LegacyDenyAllTest {
  @TestConfiguration
  @EnableWebSecurity
  @EnableWebMvc
  @Import(LegacySecurityCompatibility.class)
  static class Config {
    @Bean ZitadelFlowTest.FutureEndpoint futureEndpoint() { return new ZitadelFlowTest.FutureEndpoint(); }
  }

  @Test void inactiveLegacyFallbackCannotExposeNewControllers() throws Exception {
    try (var context = new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      context.register(Config.class);
      context.refresh();
      var mvc = MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean(FilterChainProxy.class)).build();
      assertEquals(403, mvc.perform(get("/future/operation")).andReturn().getResponse().getStatus());
    }
  }
}
