package dev.irv;

import org.springframework.context.annotation.Profile;

import static dev.irv.Util.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@Profile("!auth-only")
@RestController
public class SyncApi {
  final ConfigSync sync;
  final SandboxApi api;
  final AgentApi agent;

  public SyncApi(ConfigSync s, SandboxApi a, AgentApi g) {
    sync = s;
    api = a;
    agent = g;
  }

  @GetMapping("/api/sandboxes/{id}/sync")
  public Object status(@PathVariable String id, HttpServletRequest r) {
    api.own(id, r);
    return sync.status(id);
  }

  @PostMapping("/api/sandboxes/{id}/sync/{op}")
  public Object act(
      @PathVariable String id,
      @PathVariable String op,
      @RequestBody JsonNode b,
      HttpServletRequest r) {
    api.own(id, r);
    return sync.act(id, op, b, "app");
  }

  @GetMapping("/agent/sync")
  public Object state(HttpServletRequest r) {
    return sync.status(agent.agent(r));
  }

  @PostMapping("/agent/sync/{op}")
  public Object agentAct(@PathVariable String op, @RequestBody JsonNode b, HttpServletRequest r) {
    return sync.act(agent.agent(r), op, b, "sandbox");
  }

  @GetMapping("/agent/config/changes")
  public Object changes(@RequestParam(defaultValue = "0") long after, HttpServletRequest r) {
    return sync.pending(agent.agent(r), after);
  }

  @PostMapping("/agent/config/ack")
  public Object ack(@RequestBody JsonNode b, HttpServletRequest r) {
    return sync.ack(
        agent.agent(r),
        b.path("revision").asLong(),
        b.path("hash").asText(),
        b.path("error").asText());
  }

  @PostMapping("/agent/config/recovery")
  public Object recovery(@RequestBody JsonNode b, HttpServletRequest r) {
    return sync.preserve(agent.agent(r), b);
  }

  @GetMapping("/agent/logs")
  public Object logs(
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "") String session,
      HttpServletRequest r) {
    String id = agent.agent(r);
    return api.logRows(id, after, session);
  }
}
