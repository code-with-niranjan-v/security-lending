package com.example.lifecycle_service.scheduler;

import com.example.lifecycle_service.repository.LoanRepository;
import com.example.lifecycle_service.service.LendingFeeService;
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
public class LendingFeeScheduler {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.example.platform.WorkCoordinator coordinator;

  @org.springframework.beans.factory.annotation.Value(
    "${lifecycle.fee-accrual-cron:0 15 15 * * *}"
  )
  private String cron;

  private final LoanRepository loans;
  private final LendingFeeService fees;

  @Scheduled(
    cron = "${lifecycle.fee-accrual-cron:0 15 15 * * *}",
    zone = "Asia/Kolkata"
  )
  public void processDueFees() {
    for (long loanId : loans.findFeeLoanIds(
      fees.lastCompletedDay().plusDays(1)
    )) {
      try {
        com.example.platform.ScheduledWork.run(
          coordinator,
          "fee",
          loanId,
          cron,
          () -> {
            fees.process(loanId);
          }
        );
      } catch (RuntimeException exception) {
        log.warn(
          "Could not process lending fees for loan {}: {}",
          loanId,
          exception.getMessage()
        );
      }
    }
  }
}
