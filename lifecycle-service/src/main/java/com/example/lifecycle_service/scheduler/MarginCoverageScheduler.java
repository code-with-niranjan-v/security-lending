package com.example.lifecycle_service.scheduler;

import com.example.lifecycle_service.repository.LoanRepository;
import com.example.lifecycle_service.service.MarginCoverageService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MarginCoverageScheduler {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.example.platform.WorkCoordinator coordinator;

  @org.springframework.beans.factory.annotation.Value(
    "${lifecycle.margin-check-cron:0 15 15 * * *}"
  )
  private String cron;

  private final LoanRepository loanRepository;
  private final MarginCoverageService marginCoverageService;

  @Scheduled(
    cron = "${lifecycle.margin-check-cron:0 15 15 * * *}",
    zone = "Asia/Kolkata"
  )
  public void runCoverageCheck() {
    List<com.example.lifecycle_service.model.Loan> activeLoans =
      loanRepository.findAllByStatus("ACTIVE");
    for (var loan : activeLoans) {
      com.example.platform.ScheduledWork.run(
        coordinator,
        "margin",
        loan.getId(),
        cron,
        () -> {
          var decision = marginCoverageService.evaluateLoan(loan.getId());
          if (decision.requiresMarginCall()) {
            marginCoverageService.triggerMarginCallForLoan(
              loan.getId(),
              decision.getMarginCallAmount()
            );
          }
        }
      );
    }
  }
}
