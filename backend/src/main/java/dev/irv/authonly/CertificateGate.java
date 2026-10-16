package dev.irv.authonly;

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
    if (settings.ingressSecret.isEmpty() || request.getLocalPort() != 8081
        || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())) return false;
    String supplied = header(request, "X-Irv-Ingress-Key");
    return supplied.length() == 64 && MessageDigest.isEqual(
        settings.ingressSecret.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
  }

  String verified(HttpServletRequest request) {
    if (!trusted(request)) return null;
    String fp = header(request, "X-Irv-Cert-SHA256").toLowerCase(Locale.ROOT);
    if (!"true".equals(header(request, "X-Irv-Cert-Verified"))
        || !"false".equals(header(request, "X-Irv-Cert-Revoked"))
        || !fp.matches("[a-f0-9]{64}")) return null;
    return fp;
  }

  String approved(HttpServletRequest request) {
    return settings.certificateRequired ? verified(request) : trusted(request) ? "" : null;
  }
}
