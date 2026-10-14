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

  @Test
  void censoredAbsenceReducesHazard() {
    double initial = CostModel.hazard(900, List.of());
    double learned = CostModel.hazard(900, List.of(new CostModel.Absence(3600, false, 10)));
    assertTrue(learned < initial);
  }

  @Test
  void observedQuickReturnsIncreaseHazard() {
    assertTrue(
        CostModel.hazard(30, List.of(new CostModel.Absence(40, true, 10)))
            > CostModel.hazard(30, List.of()));
  }

  @Test
  void hardDeadlineExcludesCheapColdState() {
    var modes =
        List.of(
            new CostModel.Mode("running", .001, 0, 0, true),
            new CostModel.Mode("cold", 0, .001, 181, true));
    double[][] transitions = {{0, 0}, {Double.POSITIVE_INFINITY, 0}};
    var result = CostModel.optimize(modes, 0, 0, List.of(), transitions, 0);
    assertEquals("running", result.action());
    assertFalse(result.alternatives().containsKey("cold"));
  }

  @Test
  void inexpensiveFeasibleColdWins() {
    var modes =
        List.of(
            new CostModel.Mode("running", .001, 0, 0, true),
            new CostModel.Mode("cold", 0, 0, 60, true));
    var result =
        CostModel.optimize(
            modes, 0, 0, List.of(), new double[][] {{0, 0}, {Double.POSITIVE_INFINITY, 0}}, 0);
    assertEquals("cold", result.action());
    assertEquals(0, result.delay());
  }

  @Test
  void transitionCostPreventsWastefulChange() {
    var modes =
        List.of(
            new CostModel.Mode("running", .000001, 0, 0, true),
            new CostModel.Mode("cold", 0, 10000, 60, true));
    var result =
        CostModel.optimize(
            modes, 0, 0, List.of(), new double[][] {{0, 10000}, {Double.POSITIVE_INFINITY, 0}}, 0);
    assertEquals("running", result.action());
  }
}
