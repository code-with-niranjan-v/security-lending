package com.example.lifecycle_service.scheduler;

import com.example.lifecycle_service.repository.LoanRepository;
import com.example.lifecycle_service.service.LoanValuationService;
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
  name = { "kafka-enabled", "valuation-jobs-enabled" },
  havingValue = "true",
  matchIfMissing = true
)
public class LoanValuationScheduler {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.example.platform.WorkCoordinator coordinator;

  @org.springframework.beans.factory.annotation.Value(
    "${lifecycle.valuation-cron:0 0 * * * *}"
  )
  private String cron;

  private final LoanRepository loans;
  private final LoanValuationService valuations;

  @Scheduled(
    cron = "${lifecycle.valuation-cron:0 0 * * * *}",
    zone = "Asia/Kolkata"
  )
  public void valueActiveLoans() {
    for (var loan : loans.findAllByStatus("ACTIVE")) {
      if (
        loan.getOutstandingQuantity() == null ||
        loan.getOutstandingQuantity() <= 0
      ) continue;
      try {
        // The existing service persists the valuation and queues its report for both portals.
        com.example.platform.ScheduledWork.run(
          coordinator,
          "valuation",
          loan.getId(),
          cron,
          () -> {
            valuations.runValuationForLoan(loan.getId());
          }
        );
      } catch (RuntimeException exception) {
        log.warn("Could not value loan {}", loan.getId(), exception);
      }
    }
  }
}
