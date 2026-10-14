package dev.irv;

import static dev.irv.Util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:lifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE"
    })
class LifecycleTest {
  @Autowired Db db;

  String sandbox(String state) {
    String uid = id(), sid = id();
    db.exec(
        "INSERT INTO users(id,username,password_hash,role,created) VALUES(?,?,?,?,?)",
        uid,
        uid.substring(0, 20),
        "test",
        "user",
        now());
    db.exec(
        "INSERT INTO"
            + " sandboxes(id,owner_id,name,image_kind,image_ref,state,config,files,created,updated,machine_id,app_name,snapshot_id,lease_until)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
        sid,
        uid,
        "test",
        "blank",
        "image",
        state,
        Config.DEFAULT,
        "{}",
        now(),
        now(),
        "machine",
        "app",
        "old-snapshot");
    return sid;
  }

  @Test
  void failedSaveNeverDestroys() {
    String id = sandbox("running");
    Fly fly = mock(Fly.class);
    Hub hub = mock(Hub.class);
    Optimizer opt = mock(Optimizer.class);
    var svc = new SandboxService(db, fly, hub, opt);
    when(hub.call(eq(id), eq("save"), any(), eq(180)))
        .thenThrow(new IllegalStateException("storage down"));
    assertThrows(Exception.class, () -> svc.transition(id, "cold"));
    verify(fly, never()).destroy(any());
    assertEquals("running", svc.get(id).get("state"));
    svc.work.shutdownNow();
  }

  @Test
  void staleSnapshotAcknowledgementNeverDestroys() {
    String id = sandbox("running");
    Fly fly = mock(Fly.class);
    Hub hub = mock(Hub.class);
    var svc = new SandboxService(db, fly, hub, mock(Optimizer.class));
    when(hub.call(eq(id), eq("save"), any(), eq(180)))
        .thenReturn(node("{\"verified\":true,\"snapshot\":\"wrong-id\"}"));
    assertThrows(Exception.class, () -> svc.transition(id, "cold"));
    verify(fly, never()).destroy(any());
    svc.work.shutdownNow();
  }

  @Test
  void activeLeaseBlocksDestructiveTransition() {
    String id = sandbox("running");
    db.exec("UPDATE sandboxes SET lease_until=? WHERE id=?", now() + 60000, id);
    Fly fly = mock(Fly.class);
    Hub hub = mock(Hub.class);
    var svc = new SandboxService(db, fly, hub, mock(Optimizer.class));
    svc.transition(id, "cold");
    verifyNoInteractions(fly, hub);
    svc.work.shutdownNow();
  }

  @Test
  void stoppedMachineWithVerifiedSnapshotCanBeDestroyed() {
    String id = sandbox("stopped");
    Fly fly = mock(Fly.class);
    var svc = new SandboxService(db, fly, mock(Hub.class), mock(Optimizer.class));
    svc.transition(id, "cold");
    verify(fly).destroy(any());
    assertEquals("cold", svc.get(id).get("state"));
    assertNull(svc.get(id).get("machine_id"));
    svc.work.shutdownNow();
  }
}
