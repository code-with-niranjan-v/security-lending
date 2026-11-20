package com.example.lifecycle_service.scheduler;

import com.example.lifecycle_service.repository.LoanRepository;
import com.example.lifecycle_service.service.RebateLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
  prefix = "lifecycle",
  name = { "kafka-enabled", "accrual-jobs-enabled" },
  havingValue = "true",
  matchIfMissing = true
)
public class RebateScheduler {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.example.platform.WorkCoordinator coordinator;

  @org.springframework.beans.factory.annotation.Value(
    "${lifecycle.rebate-accrual-cron:0 15 15 * * *}"
  )
  private String cron;

  private final LoanRepository loans;
  private final RebateLifecycleService rebates;

  @Scheduled(
    cron = "${lifecycle.rebate-accrual-cron:0 15 15 * * *}",
    zone = "Asia/Kolkata"
  )
  public void processDueRebates() {
    for (long loanId : loans.findRebateLoanIds()) {
      try {
        com.example.platform.ScheduledWork.run(
          coordinator,
          "rebate",
          loanId,
          cron,
          () -> {
            rebates.process(loanId);
          }
        );
      } catch (RuntimeException exception) {
        log.error("Could not process rebates for loan {}", loanId, exception);
      }
    }
  }
}
