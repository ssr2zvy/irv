package dev.irv;

import org.junit.jupiter.api.Test;

class LifecycleTest {
  @Test
  void fixedPolicyAndFailureRecovery() throws Exception {
    FixedPolicyChecks.runAll();
  }
}
