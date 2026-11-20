package com.example.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

@EnabledIfEnvironmentVariable(
  named = "PLATFORM_TEST_DATABASE_URL",
  matches = ".+"
)
class WorkCoordinatorTest {

  JdbcTemplate db;
  WorkCoordinator first, second;

  @BeforeEach
  void prepare() {
    var source = new DriverManagerDataSource(
      System.getenv("PLATFORM_TEST_DATABASE_URL"),
      System.getenv().getOrDefault("PLATFORM_TEST_DATABASE_USER", "postgres"),
      System.getenv().getOrDefault("PLATFORM_TEST_DATABASE_PASSWORD", "")
    );
    db = new JdbcTemplate(source);
    db.execute(
      "DROP TABLE IF EXISTS platform_worker_lease,platform_work_completion,platform_test_balance"
    );
    db.execute(
      "CREATE TABLE platform_worker_lease(job VARCHAR(250) PRIMARY KEY,owner VARCHAR(250) NOT NULL,fence BIGINT NOT NULL,expires_at TIMESTAMPTZ NOT NULL)"
    );
    db.execute(
      "CREATE TABLE platform_work_completion(job VARCHAR(120),entity_id BIGINT,period VARCHAR(120),completed_at TIMESTAMPTZ,PRIMARY KEY(job,entity_id,period))"
    );
    db.execute(
      "CREATE TABLE platform_test_balance(id INT PRIMARY KEY, amount INT)"
    );
    db.update("INSERT INTO platform_test_balance VALUES (1,0)");
    first = new WorkCoordinator(
      db,
      new DataSourceTransactionManager(source),
      "first"
    );
    second = new WorkCoordinator(
      db,
      new DataSourceTransactionManager(source),
      "second"
    );
  }

  @AfterEach
  void close() {
    first.close();
    second.close();
  }

  void credit() {
    db.update("UPDATE platform_test_balance SET amount=amount+1 WHERE id=1");
  }

  int balance() {
    return db.queryForObject(
      "SELECT amount FROM platform_test_balance WHERE id=1",
      Integer.class
    );
  }

  @Test
  void expiredOwnerCannotRenewOrReleaseSuccessor() {
    var old = first.acquire("fees");
    assertNotNull(old);
    assertNull(second.acquire("fees"));
    db.update(
      "UPDATE platform_worker_lease SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'"
    );
    var replacement = second.acquire("fees");
    assertTrue(replacement.fence() > old.fence());
    assertFalse(first.renew(old));
    first.release(old);
    assertTrue(second.renew(replacement));
    assertNull(first.acquire("fees"));
  }

  @Test
  void concurrentReplicasCommitOneFinancialEffectAndCompletion() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var a = executor.submit(() -> {
        await(start);
        first.once("fee", 42, "2026-11-20", this::credit);
      });
      var b = executor.submit(() -> {
        await(start);
        second.once("fee", 42, "2026-11-20", this::credit);
      });
      start.countDown();
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
      assertEquals(1, balance());
      assertEquals(
        1,
        db.queryForObject(
          "SELECT COUNT(*) FROM platform_work_completion",
          Integer.class
        )
      );
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void failedTransactionRollsBackAndTakeoverCompletes() {
    assertThrows(IllegalStateException.class, () ->
      first.once("valuation", 42, "period", () -> {
        credit();
        throw new IllegalStateException("crash");
      })
    );
    assertEquals(0, balance());
    second.once("valuation", 42, "period", this::credit);
    first.once("valuation", 42, "period", this::credit);
    assertEquals(1, balance());
  }

  @Test
  void staleWorkerCannotApplyFinancialWork() {
    assertThrows(IllegalStateException.class, () ->
      first.lead("margin", () -> {
        db.update(
          "UPDATE platform_worker_lease SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'"
        );
        second.acquire("margin");
        first.once("margin", 42, "period", this::credit);
      })
    );
    assertEquals(0, balance());
  }

  @Test
  void organizationsAndPeriodsRemainIndependent() {
    first.once("fee", 42, "day1", this::credit);
    second.once("fee", 43, "day1", this::credit);
    second.once("fee", 42, "day2", this::credit);
    assertEquals(3, balance());
  }

  static void await(CountDownLatch start) {
    try {
      start.await();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void killedWorkerRollsBackAndAnotherJvmCanTakeOverAfterLeaseExpiry() throws Exception {
    String javaExecutable = System.getProperty("java.home") + "/bin/java";
    String classpath = System.getProperty(
      "surefire.test.class.path",
      System.getProperty("java.class.path")
    );
    var child = new ProcessBuilder(
      javaExecutable,
      "-Xmx96m",
      "-cp",
      classpath,
      CrashWorker.class.getName()
    )
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start();
    try {
      var output = new java.io.BufferedReader(
        new java.io.InputStreamReader(child.getInputStream())
      );
      var marker = CompletableFuture.supplyAsync(() -> {
        try {
          return output.readLine();
        } catch (java.io.IOException e) {
          throw new IllegalStateException(e);
        }
      });
      assertEquals("transaction-open", marker.get(15, TimeUnit.SECONDS));
      child.destroyForcibly();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS));
      assertEquals(0, balance());
      assertEquals(
        0,
        db.queryForObject(
          "SELECT COUNT(*) FROM platform_work_completion",
          Integer.class
        )
      );
      org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(50))
        .pollInterval(java.time.Duration.ofSeconds(1))
        .until(() -> {
          second.lead("crash-probe", () ->
            second.once("fee", 42, "crash-period", this::credit)
          );
          return balance() == 1;
        });
      first.once("fee", 42, "crash-period", this::credit);
      assertEquals(1, balance());
      assertEquals(
        1,
        db.queryForObject(
          "SELECT COUNT(*) FROM platform_work_completion",
          Integer.class
        )
      );
    } finally {
      child.destroyForcibly();
    }
  }

  public static class CrashWorker {

    public static void main(String[] args) {
      var source = new DriverManagerDataSource(
        System.getenv("PLATFORM_TEST_DATABASE_URL"),
        System.getenv().getOrDefault("PLATFORM_TEST_DATABASE_USER", "postgres"),
        System.getenv().getOrDefault("PLATFORM_TEST_DATABASE_PASSWORD", "")
      );
      var jdbc = new JdbcTemplate(source);
      try (
        var worker = new WorkCoordinator(
          jdbc,
          new DataSourceTransactionManager(source),
          "killed-process"
        )
      ) {
        worker.lead("crash-probe", () ->
          worker.once("fee", 42, "crash-period", () -> {
            jdbc.update(
              "UPDATE platform_test_balance SET amount=amount+1 WHERE id=1"
            );
            System.out.println("transaction-open");
            System.out.flush();
            await(new CountDownLatch(1));
          })
        );
      }
    }
  }
}
