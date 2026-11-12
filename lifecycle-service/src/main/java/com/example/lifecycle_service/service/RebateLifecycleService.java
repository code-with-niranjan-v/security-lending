package com.example.lifecycle_service.service;

import com.example.lifecycle_service.dto.CashCollateralChange;
import com.example.lifecycle_service.dto.RebateRunResult;
import com.example.lifecycle_service.dto.RebateTodayResult;
import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class RebateLifecycleService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
  private final LoanRepository loans;
  private final MslaRepository agreements;
  private final CashCollateralMovementRepository collateral;
  private final RebateDayRepository days;
  private final LenderOutboxEventRepository outbox;
  private final ObjectMapper json;
  private final Clock clock;

  @Value("${lifecycle.lender-events-topic:lender.lifecycle.events.v1}")
  private String topic;

  @Transactional
  public RebateRunResult process(long loanId) {
    var loan = lockLoan(loanId);
    if (loan.getSettledAt() == null) {
      return new RebateRunResult(loanId, null, 0, 0, BigDecimal.ZERO);
    }
    var agreement = agreements.findById(loan.getMslaId()).orElseThrow();
    Instant now = clock.instant();
    LocalDate closedOn = loan.getClosedAt() == null ? null : businessDate(loan.getClosedAt());
    LocalDate through = closedOn == null ? businessDate(now) : closedOn;
    LocalDate first = loan.getRebateProcessedThrough() == null
        ? businessDate(loan.getSettledAt()) : loan.getRebateProcessedThrough().plusDays(1);
    // Closing on the same day as a manual accrual leaves first after through. Existing unsettled
    // rows still have to be evaluated for an AT_CLOSE settlement.
    int recorded = 0;
    if (agreement.getRebateRate().signum() > 0 && !first.isAfter(through)) {
      recorded = accrueDays(loan, agreement, first, through, now);
    }
    var frequency = RebateSchedule.valueOf(agreement.getRebateSettlementFrequency());
    LocalDate settlementThrough=closedOn==null?businessDate(now).minusDays(1):closedOn;
    var due = new TreeMap<LocalDate, List<RebateDay>>();
    for (var day : days.findByLoanIdAndSettlementReferenceIdIsNullOrderByAccrualDateAsc(loanId)) {
      LocalDate end = frequency.periodEnd(day.getAccrualDate(), closedOn);
      if (end != null && !end.isAfter(settlementThrough)) {
        due.computeIfAbsent(end, ignored -> new ArrayList<>()).add(day);
      }
    }

    int settlements = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (var period : due.entrySet()) {
      BigDecimal amount = settle(loan, agreement, period.getValue(), period.getKey(), closedOn, now);
      if (amount.signum() > 0) {
        settlements++;
        total = total.add(amount);
      }
    }
    if (!first.isAfter(through)) loan.setRebateProcessedThrough(through);
    return new RebateRunResult(loanId, through, recorded, settlements, total);
  }

  @Transactional
  public RebateTodayResult accrueToday(long loanId) {
    var loan = lockLoan(loanId);
    Instant now = clock.instant();
    LocalDate today = businessDate(now);
    var existing = days.findByLoanIdAndAccrualDate(loanId, today);
    if (existing.isPresent()) {
      var day = existing.get();
      return new RebateTodayResult(loanId, today, day.getCashBalance(), day.getAmount(), false);
    }
    if (loan.getSettledAt() == null || loan.getSettledAt().isAfter(now) || loan.getClosedAt() != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Loan must be settled and open");
    }
    if (loan.getRebateProcessedThrough() != null && !loan.getRebateProcessedThrough().isBefore(today)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Rebates have already been processed through today");
    }
    var agreement = agreements.findById(loan.getMslaId()).orElseThrow();
    if (agreement.getRebateRate().signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "MSLA must have a positive rebate rate");
    }
    var balance = collateral.cashChangesBefore(loanId, now).stream()
        .map(CashCollateralChange::balanceChange)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (balance.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Loan has no confirmed cash collateral");
    }
    var day = recordDay(loan, agreement, today, balance, now);
    return new RebateTodayResult(loanId, today, day.getCashBalance(), day.getAmount(), true);
  }

  private Loan lockLoan(long loanId) {
    long org = loans.findOrganizationId(loanId).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found"));
    // Serialize with loan settlement, cash movements and parallel rebate jobs.
    loans.lockOrganization(org);
    return loans.findByIdAndOrganizationId(loanId, org).orElseThrow();
  }

  private int accrueDays(Loan loan, Msla agreement, LocalDate first, LocalDate through, Instant now) {
    var changes = collateral.cashChangesBefore(loan.getId(), cutoff(loan, through, now));
    int changeIndex = 0;
    int recorded = 0;
    BigDecimal balance = BigDecimal.ZERO;
    for (LocalDate day = first; !day.isAfter(through); day = day.plusDays(1)) {
      Instant end = cutoff(loan, day, now);
      while (changeIndex < changes.size() && changes.get(changeIndex).recordedAt().isBefore(end)) {
        balance = balance.add(changes.get(changeIndex++).balanceChange());
      }
      if (balance.signum() <= 0 || days.findByLoanIdAndAccrualDate(loan.getId(), day).isPresent()) {
        continue;
      }
      recordDay(loan, agreement, day, balance, now);
      recorded++;
    }
    return recorded;
  }

  private RebateDay recordDay(Loan loan, Msla agreement, LocalDate day, BigDecimal balance, Instant now) {
    BigDecimal amount = balance.multiply(agreement.getRebateRate())
        .divide(BigDecimal.valueOf(agreement.getDayBasis()), 6, RoundingMode.HALF_UP);
    var record = days.save(new RebateDay(loan.getId(), day, balance, amount));
    enqueue(loan, record.getId(), "rebate-day-", "ACCRUAL_REPORTED", 1, now, Map.of(
        "loanId", loan.getId(), "type", "REBATE", "date", day.toString(),
        "baseValue", balance, "rate", agreement.getRebateRate(), "amount", amount));
    return record;
  }

  private BigDecimal settle(Loan loan, Msla agreement, List<RebateDay> period, LocalDate end,
      LocalDate closedOn, Instant now) {
    var first = period.get(0);
    long reference = first.getId();
    BigDecimal total = period.stream().map(RebateDay::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    for (var day : period) day.setSettlementReferenceId(reference);
    if (total.signum() == 0) return total;

    LocalDate paymentDate = end.equals(closedOn) ? end : end.plusDays(1);
    enqueue(loan, reference, "rebate-settlement-", "REBATE_ACCRUED", 1, now, Map.of(
        "loanId", loan.getId(), "amount", total, "rate", agreement.getRebateRate(),
        "date", end.toString(), "periodStart", first.getAccrualDate().toString(),
        "periodEnd", end.toString(), "paymentDate", paymentDate.toString(),
        "settlementFrequency", agreement.getRebateSettlementFrequency()));
    enqueue(loan, reference, "rebate-payment-", "REBATE_PAID", 2, now,
        Map.of("loanId", loan.getId(), "amount", total));
    return total;
  }

  private Instant cutoff(Loan loan, LocalDate day, Instant now) {
    Instant dayEnd = day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
    Instant cutoff =
        loan.getClosedAt() != null && loan.getClosedAt().isBefore(dayEnd)
            ? loan.getClosedAt()
            : dayEnd;
    return cutoff.isAfter(now) ? now : cutoff;
  }

  private LocalDate businessDate(Instant instant) {
    return instant.atZone(BUSINESS_ZONE).toLocalDate();
  }

  private void enqueue(Loan loan, long reference, String prefix, String type, int version, Instant now,
      Map<String, Object> data) {
    String eventId = prefix + reference;
    try {
      String payload =
          json.writeValueAsString(
              Map.of(
                  "eventId",
                  eventId,
                  "type",
                  type,
                  "organizationId",
                  loan.getOrganizationId(),
                  "referenceId",
                  reference,
                  "version",
                  version,
                  "occurredAt",
                  now.toString(),
                  "data",
                  data));
      outbox.save(new LenderOutboxEvent(loan.getOrganizationId(), eventId, topic, payload, now));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize rebate event " + eventId, exception);
    }
  }
}
