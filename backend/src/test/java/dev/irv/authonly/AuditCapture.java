package dev.irv.authonly;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

final class AuditCapture implements AutoCloseable {
  final Logger logger = (Logger) LoggerFactory.getLogger("irv.auth.audit");
  final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  AuditCapture() { appender.start(); logger.addAppender(appender); }
  List<JsonNode> rows() {
    ObjectMapper json = new ObjectMapper();
    return appender.list.stream().map(e -> {
      assertNull(e.getThrowableProxy(), "Audit events must never attach exceptions");
      try { return json.readTree(e.getFormattedMessage()); }
      catch (Exception ex) { throw new AssertionError(ex); }
    }).toList();
  }
  void has(String event, String outcome, String reason) {
    assertTrue(rows().stream().anyMatch(r -> r.path("event").asText().equals(event)
        && r.path("outcome").asText().equals(outcome) && r.path("reason").asText().equals(reason)),
        event + "/" + outcome + "/" + reason);
  }
  void excludes(String... secrets) {
    String all = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
    for (String secret : secrets) assertFalse(all.contains(secret), "Sensitive value leaked");
    Set<String> keys = Set.of("schema", "timestamp", "event", "outcome", "reason", "request_id", "method",
        "route", "flow_id", "session_ref", "status", "duration_ms");
    for (JsonNode row : rows()) {
      row.fieldNames().forEachRemaining(k -> assertTrue(keys.contains(k), "Unexpected field: " + k));
      UUID.fromString(row.path("request_id").asText());
    }
  }
  public void close() { logger.detachAppender(appender); appender.stop(); }
}
