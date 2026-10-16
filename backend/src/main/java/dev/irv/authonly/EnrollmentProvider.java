package dev.irv.authonly;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Fixed provider endpoints, no redirects, no automatic retries, no credential logging. */
class EnrollmentProvider {
  private final AuthSettings settings;
  private final ObjectMapper mapper;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  EnrollmentProvider(AuthSettings settings, ObjectMapper mapper) { this.settings = settings; this.mapper = mapper; }

  String create(String username, String email, String given, String family) {
    JsonNode result = call("/v2/users/new", Map.of("organizationId", AuthSettings.ORG, "username", username,
        "human", Map.of("profile", Map.of("givenName", given, "familyName", family),
            // Provider administrative flag suppresses email-code delivery. This is NOT proof of mailbox ownership.
            "email", Map.of("email", email, "isVerified", true))));
    String id = result.path("id").asText();
    if (!id.matches("[0-9]{1,30}")) throw new IllegalStateException("Unexpected provider user identifier");
    return id;
  }
  String registrationLink(String subject) {
    JsonNode code = call("/v2/users/" + subject + "/passkeys/registration_link", Map.of("returnCode", Map.of())).path("code");
    String id = code.path("id").asText(), value = code.path("code").asText();
    if (id.isBlank() || value.isBlank() || id.length() > 200 || value.length() > 200)
      throw new IllegalStateException("Missing provider enrollment code");
    // Login V2's passkey/set route accepts userId, organization, codeId and code.
    return AuthSettings.ISSUER + "/ui/v2/login/passkey/set?userId=" + encode(subject)
        + "&organization=" + encode(AuthSettings.ORG) + "&codeId=" + encode(id) + "&code=" + encode(value);
  }
  void grant(String subject) {
    call("/management/v1/users/" + subject + "/grants", Map.of("projectId", AuthSettings.PROJECT, "roleKeys", List.of(AuthSettings.ROLE)));
  }
  protected JsonNode call(String path, Object body) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(AuthSettings.ISSUER + path))
          .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + settings.enrollmentToken)
          .header("x-zitadel-orgid", AuthSettings.ORG).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length() > 65536)
        throw new IllegalStateException();
      return mapper.readTree(response.body());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt(); throw new IllegalStateException("Provider enrollment unavailable");
    } catch (Exception ex) { throw new IllegalStateException("Provider enrollment unavailable"); }
  }
  private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
}
