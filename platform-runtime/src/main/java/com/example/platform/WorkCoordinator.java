package com.example.platform;

import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL leases use database time; completion and financial effects share one transaction. */
public class WorkCoordinator implements AutoCloseable {

  private final JdbcTemplate db;
  private final TransactionTemplate transaction;
  private final String owner;
  private final ScheduledExecutorService renewer =
    Executors.newScheduledThreadPool(1, r -> {
      var thread = new Thread(r, "worker-lease-renewal");
      thread.setDaemon(true);
      return thread;
    });
  private final ThreadLocal<Lease> current = new ThreadLocal<>();

  public record Lease(String job, long fence) {}

  public WorkCoordinator(
    JdbcTemplate db,
    PlatformTransactionManager manager,
    String instance
  ) {
    this.db = db;
    this.owner = instance + ":" + UUID.randomUUID();
    this.transaction = new TransactionTemplate(manager);
    transaction.setTimeout(30);
  }

  public Lease acquire(String job) {
    db.update(
      "INSERT INTO platform_worker_lease(job,owner,fence,expires_at) VALUES (?, '', 0, clock_timestamp()) ON CONFLICT DO NOTHING",
      job
    );
    var fences = db.query(
      "UPDATE platform_worker_lease SET owner=?, fence=fence+1, expires_at=clock_timestamp()+INTERVAL '45 seconds' WHERE job=? AND expires_at<=clock_timestamp() RETURNING fence",
      (rs, n) -> rs.getLong(1),
      owner,
      job
    );
    if (fences.isEmpty()) return null;
    io.micrometer.core.instrument.Metrics.counter(
      "platform.worker.claims",
      "job",
      job
    ).increment();
    return new Lease(job, fences.get(0));
  }

  public boolean renew(Lease lease) {
    return (
      db.update(
        "UPDATE platform_worker_lease SET expires_at=clock_timestamp()+INTERVAL '45 seconds' WHERE job=? AND owner=? AND fence=? AND expires_at>clock_timestamp()",
        lease.job(),
        owner,
        lease.fence()
      ) == 1
    );
  }

  public void release(Lease lease) {
    db.update(
      "UPDATE platform_worker_lease SET expires_at=clock_timestamp() WHERE job=? AND owner=? AND fence=?",
      lease.job(),
      owner,
      lease.fence()
    );
  }

  public void lead(String job, Runnable work) {
    Lease lease = acquire(job);
    if (lease == null) return;
    var heartbeat = renewer.scheduleAtFixedRate(
      () -> {
        try {
          renew(lease);
        } catch (RuntimeException exception) {
          org.slf4j.LoggerFactory.getLogger(getClass()).warn(
            "Lease renewal failed for {}",
            job,
            exception
          );
        }
      },
      10,
      10,
      TimeUnit.SECONDS
    );
    Lease previous = current.get();
    current.set(lease);
    try {
      work.run();
    } finally {
      if (previous == null) current.remove();
      else current.set(previous);
      heartbeat.cancel(false);
      release(lease);
    }
  }

  /** Stable key per job/entity/period. Rollback leaves the work eligible for takeover. */
  public void once(String job, long entity, String period, Runnable work) {
    transaction.executeWithoutResult(status -> {
      Lease lease = current.get();
      if (lease != null) {
        var valid = db.queryForList(
          "SELECT fence FROM platform_worker_lease WHERE job=? AND owner=? AND fence=? AND expires_at>clock_timestamp() FOR UPDATE",
          lease.job(),
          owner,
          lease.fence()
        );
        if (valid.isEmpty()) throw new IllegalStateException(
          "Worker lease expired: " + lease.job()
        );
      }
      int inserted = db.update(
        "INSERT INTO platform_work_completion(job,entity_id,period,completed_at) VALUES (?,?,?,clock_timestamp()) ON CONFLICT DO NOTHING",
        job,
        entity,
        period
      );
      if (inserted == 1) {
        work.run();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              io.micrometer.core.instrument.Metrics.counter(
                "platform.work.completed",
                "job",
                job
              ).increment();
            }
          }
        );
      } else io.micrometer.core.instrument.Metrics.counter(
        "platform.work.deduplicated",
        "job",
        job
      ).increment();
    });
  }

  @Override
  public void close() {
    renewer.shutdownNow();
  }
}
