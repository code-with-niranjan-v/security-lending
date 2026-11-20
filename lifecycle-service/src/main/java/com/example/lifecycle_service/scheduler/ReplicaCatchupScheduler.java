package com.example.lifecycle_service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles missed cron ticks after a process crash; per-loan completion keys avoid repeat effects. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
  name = "platform.coordination.enabled",
  havingValue = "true"
)
public class ReplicaCatchupScheduler {

  private final ObjectProvider<LoanValuationScheduler> valuations;
  private final ObjectProvider<LendingFeeScheduler> fees;
  private final ObjectProvider<RebateScheduler> rebates;
  private final ObjectProvider<MarginCoverageScheduler> margins;

  @Scheduled(fixedDelayString = "${platform.schedule-reconcile-ms:10000}")
  public void reconcile() {
    valuations.ifAvailable(LoanValuationScheduler::valueActiveLoans);
    fees.ifAvailable(LendingFeeScheduler::processDueFees);
    rebates.ifAvailable(RebateScheduler::processDueRebates);
    margins.ifAvailable(MarginCoverageScheduler::runCoverageCheck);
  }
}
