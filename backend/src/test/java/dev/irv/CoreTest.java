package dev.irv;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class CoreTest {
  @Test
  void totpRfc6238SixDigitVectorAndReplay() {
    String s = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    assertEquals("287082", Crypto.otp(s, 1));
    assertEquals(1, Crypto.verify(s, "287082", -1, 59000));
    assertEquals(-1, Crypto.verify(s, "287082", 1, 59000));
  }

  @Test
  void configurationIsRestricted() {
    assertEquals("workspace", Config.parse(Config.DEFAULT).getString("folder_name"));
    assertThrows(Exception.class, () -> Config.parse(Config.DEFAULT + "\n[services]\nport=22"));
    assertThrows(Exception.class, () -> Config.relative("../etc/passwd"));
    assertThrows(Exception.class, () -> Config.relative("/etc/passwd"));
    assertThrows(
        Exception.class, () -> Config.parse(Config.DEFAULT.replace("workspace", "../../outside")));
  }
}
