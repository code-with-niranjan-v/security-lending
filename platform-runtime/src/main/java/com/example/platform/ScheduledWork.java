package com.example.platform;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

public final class ScheduledWork {

  private ScheduledWork() {}

  public static void run(
    WorkCoordinator coordinator,
    String job,
    long entity,
    String cron,
    Runnable work
  ) {
    if (coordinator == null) {
      work.run();
      return;
    }
    var now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
    var expression = CronExpression.parse(cron);
    var latest = expression.next(now.minusDays(2));
    if (latest == null) throw new IllegalArgumentException(
      "Unsupported schedule: " + cron
    );
    for (
      var next = expression.next(latest);
      next != null && !next.isAfter(now);
      next = expression.next(latest)
    ) latest = next;
    coordinator.once(job, entity, latest.toInstant().toString(), work);
  }
}
