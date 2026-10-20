package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class CertificateGate {
  private final AuthSettings settings;
  CertificateGate(AuthSettings settings) { this.settings = settings; }

  static String header(HttpServletRequest request, String name) {
    List<String> values = Collections.list(request.getHeaders(name));
    return values.size() == 1 ? values.get(0) : "";
  }

  boolean trusted(HttpServletRequest request) {
    Reason reason = settings.ingressSecret.isEmpty() ? Reason.NOT_CONFIGURED
        : request.getLocalPort() != 8081 || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
        ? Reason.UNTRUSTED_CONNECTION : Reason.VALID;
    if (reason == Reason.VALID) {
      String supplied = header(request, "X-Irv-Ingress-Key");
      if (supplied.length() != 64 || !MessageDigest.isEqual(
          settings.ingressSecret.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII)))
        reason = Reason.INVALID_SECRET;
    }
    audit(Stage.INGRESS, reason == Reason.VALID ? Outcome.PASSED : Outcome.FAILED, reason);
    return reason == Reason.VALID;
  }

  String verified(HttpServletRequest request) {
    if (!trusted(request)) return null;
    String fp = header(request, "X-Irv-Cert-SHA256").toLowerCase(Locale.ROOT);
    Reason reason = !"true".equals(header(request, "X-Irv-Cert-Verified")) ? Reason.NOT_VERIFIED
        : !"false".equals(header(request, "X-Irv-Cert-Revoked")) ? Reason.REVOKED_OR_UNKNOWN
        : !fp.matches("[a-f0-9]{64}") ? Reason.INVALID_FINGERPRINT : Reason.VALID;
    audit(Stage.CERTIFICATE, reason == Reason.VALID ? Outcome.PASSED : Outcome.FAILED, reason);
    return reason == Reason.VALID ? fp : null;
  }

  String approved(HttpServletRequest request) {
    if (settings.certificateRequired) return verified(request);
    audit(Stage.CERTIFICATE, Outcome.NOT_REQUIRED, Reason.POLICY_DISABLED);
    return trusted(request) ? "" : null;
  }
}
