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
    name = {"kafka-enabled", "accrual-jobs-enabled"},
    havingValue = "true",
    matchIfMissing = true)
public class LendingFeeScheduler {
  private final LoanRepository loans;
  private final LendingFeeService fees;

  @Scheduled(fixedDelayString = "${lifecycle.fee-poll-ms:60000}")
  public void processDueFees() {
    for (long loanId : loans.findFeeLoanIds(fees.lastCompletedDay())) {
      try {
        fees.process(loanId);
      } catch (RuntimeException exception) {
        log.warn("Could not process lending fees for loan {}: {}", loanId, exception.getMessage());
      }
    }
  }
}
