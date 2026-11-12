package com.example.lifecycle_service.service;

import com.example.lifecycle_service.dto.LendingFeeRunResult;
import com.example.lifecycle_service.dto.LendingFeeTodayResult;
import com.example.lifecycle_service.model.FeeSecurity;
import com.example.lifecycle_service.model.LenderOutboxEvent;
import com.example.lifecycle_service.model.LendingFeeDay;
import com.example.lifecycle_service.model.Loan;
import com.example.lifecycle_service.model.Msla;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class LendingFeeService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
  private final LoanRepository loans;
  private final MslaRepository agreements;
  private final FeeLoanReturnRepository returns;
  private final FeeSecurityRepository securities;
  private final FeeMarketPrices market;
  private final LendingFeeDayRepository days;
  private final LenderOutboxEventRepository outbox;
  private final ObjectMapper json;
  private final Clock clock;

  @Value("${lifecycle.lender-events-topic:lender.lifecycle.events.v1}")
  private String topic;

  public LocalDate lastCompletedDay() {
    return date(clock.instant()).minusDays(1);
  }

  @Transactional
  public LendingFeeRunResult process(long loanId) {
    var loan = lockLoan(loanId);
    var cursor = loan.getFeeProcessedThrough();
    if (loan.getSettledAt() == null) {
      return new LendingFeeRunResult(loanId, cursor, 0, 0, BigDecimal.ZERO);
    }
    var agreement = agreements.findById(loan.getMslaId()).orElseThrow();
    Instant now = clock.instant();
    LocalDate closed = loan.getClosedAt() == null ? null : date(loan.getClosedAt());
    LocalDate through = closed == null ? date(now) : closed;
    LocalDate first = cursor == null ? date(loan.getSettledAt()) : cursor.plusDays(1);
    // A demo accrual can already have advanced the cursor to the close date. Do not return
    // early in that case because an existing same-day accrual can still be unbilled.
    int count = 0;
    if (agreement.getFeeRate().signum() > 0 && !first.isAfter(through)) {
      count = accrueDays(loan, agreement, first, through, now);
    }
    LocalDate settlementThrough=closed==null?date(now).minusDays(1):closed;
    var due = new TreeMap<LocalDate, List<LendingFeeDay>>();
    for (var day : days.findByLoanIdAndBilledFalseOrderByAccrualDateAsc(loanId)) {
      if (day.getAccrualDate().isAfter(through)) continue;
      LocalDate end = billingEnd(agreement, day.getAccrualDate(), closed);
      if (end != null && !end.isAfter(settlementThrough)) {
        due.computeIfAbsent(end, ignored -> new ArrayList<>()).add(day);
      }
    }

    int bills = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (var period : due.entrySet()) {
      BigDecimal amount = bill(loan, agreement, period.getKey(), closed, period.getValue(), now);
      if (amount.signum() > 0) {
        bills++;
        total = total.add(amount);
      }
    }
    if (!first.isAfter(through)) loan.setFeeProcessedThrough(through);
    if (closed != null) loan.setFeeFinalized(true);
    return new LendingFeeRunResult(loanId, through, count, bills, total);
  }

  @Transactional
  public LendingFeeTodayResult accrueToday(long loanId) {
    var loan = lockLoan(loanId);
    Instant now = clock.instant();
    LocalDate today = date(now);
    var existing = days.findByLoanIdAndAccrualDate(loanId, today);
    if (existing.isPresent()) {
      var day = existing.get();
      return new LendingFeeTodayResult(loanId, today, day.getMarketValue(), day.getAmount(), false);
    }
    if (loan.getSettledAt() == null || loan.getSettledAt().isAfter(now)
        || loan.getClosedAt() != null || loan.getOutstandingQuantity() <= 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Loan must be settled and open");
    }
    if (loan.getFeeProcessedThrough() != null && !loan.getFeeProcessedThrough().isBefore(today)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Fees have already been processed through today");
    }
    var agreement = agreements.findById(loan.getMslaId()).orElseThrow();
    if (agreement.getFeeRate().signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "MSLA must have a positive lending fee rate");
    }
    var mark = market.history(security(loan), now, true).floorEntry(now);
    if (mark == null) throw missingPrice(today);
    var day = recordDay(loan, agreement, today, loan.getOutstandingQuantity(), mark.getValue(), now);
    return new LendingFeeTodayResult(loanId, today, day.getMarketValue(), day.getAmount(), true);
  }

  private int accrueDays(Loan loan, Msla agreement, LocalDate first, LocalDate through, Instant now) {
    var settledReturns = returns.findByLoanIdAndStatusAndSettledAtBeforeOrderBySettledAtAscIdAsc(
        loan.getId(), "SETTLED", cutoff(loan, through, now));
    NavigableMap<Instant, BigDecimal> marks = null;
    long outstanding = loan.getQuantity();
    int returnIndex = 0;
    int count = 0;
    for (LocalDate day = first; !day.isAfter(through); day = day.plusDays(1)) {
      Instant end = cutoff(loan, day, now);
      while (returnIndex < settledReturns.size()
          && !date(settledReturns.get(returnIndex).getSettledAt()).isAfter(day)) {
        outstanding -= settledReturns.get(returnIndex++).getQuantity();
      }
      if (outstanding <= 0 || days.findByLoanIdAndAccrualDate(loan.getId(), day).isPresent()) continue;
      if (marks == null) marks = market.history(security(loan), cutoff(loan, through, now), false);
      var mark = marks.floorEntry(end);
      if (mark == null) throw missingPrice(day);
      recordDay(loan, agreement, day, outstanding, mark.getValue(), now);
      count++;
    }
    return count;
  }

  private LendingFeeDay recordDay(Loan loan, Msla agreement, LocalDate date,
      long quantity, BigDecimal price, Instant now) {
    BigDecimal marketValue = price.multiply(BigDecimal.valueOf(quantity)).setScale(6, RoundingMode.HALF_UP);
    BigDecimal amount = marketValue.multiply(agreement.getFeeRate())
        .divide(BigDecimal.valueOf(agreement.getDayBasis()), 6, RoundingMode.HALF_UP);
    var day = days.save(new LendingFeeDay(loan.getId(), date, marketValue, amount));
    publish(loan, day.getId(), "fee-day-", "ACCRUAL_REPORTED", now, Map.of(
        "loanId", loan.getId(), "type", "FEE", "date", date.toString(),
        "baseValue", marketValue, "rate", agreement.getFeeRate(), "amount", amount));
    return day;
  }

  private BigDecimal bill(Loan loan, Msla agreement, LocalDate end, LocalDate closed,
      List<LendingFeeDay> period, Instant now) {
    var first = period.get(0);
    BigDecimal total = period.stream().map(LendingFeeDay::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    for (var day : period) day.setBilled(true);
    if (total.signum() > 0) {
      LocalDate dueDate = end.equals(closed) ? end : end.plusDays(1);
      publish(loan, first.getId(), "fee-bill-", "FEE_BILL_ISSUED", now, Map.of(
          "loanId", loan.getId(), "amount", total, "rate", agreement.getFeeRate(),
          "periodStart", first.getAccrualDate().toString(), "periodEnd", end.toString(),
          "dueDate", dueDate.toString()));
    }
    return total;
  }

  private void publish(Loan loan, long ref, String prefix, String type, Instant now, Map<String, Object> data) {
    String eventId = prefix + ref;
    try {
      String payload = json.writeValueAsString(Map.of(
          "eventId", eventId, "type", type, "organizationId", loan.getOrganizationId(),
          "referenceId", ref, "version", 1, "occurredAt", now.toString(), "data", data));
      outbox.save(new LenderOutboxEvent(loan.getOrganizationId(), eventId, topic, payload, now));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize fee event " + eventId, exception);
    }
  }

  private Loan lockLoan(long loanId) {
    long org = loans.findOrganizationId(loanId).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found"));
    loans.lockOrganization(org);
    return loans.findByIdAndOrganizationId(loanId, org).orElseThrow();
  }

  private FeeSecurity security(Loan loan) {
    return securities
        .findById(loan.getSecurityId())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "Loan security is missing from market data"));
  }

  private static ResponseStatusException missingPrice(LocalDate date) {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "No completed market price available by " + date);
  }

  private static LocalDate billingEnd(Msla agreement, LocalDate day, LocalDate closed) {
    if ("AT_CLOSE".equals(agreement.getBillingFrequency())) return closed;
    LocalDate monthEnd = day.with(TemporalAdjusters.lastDayOfMonth());
    return closed != null && closed.isBefore(monthEnd) ? closed : monthEnd;
  }

  private static Instant cutoff(Loan loan, LocalDate day, Instant now) {
    Instant end = day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
    Instant cutoff =
        loan.getClosedAt() != null && loan.getClosedAt().isBefore(end) ? loan.getClosedAt() : end;
    return cutoff.isAfter(now) ? now : cutoff;
  }

  private static LocalDate date(Instant instant) {
    return instant.atZone(BUSINESS_ZONE).toLocalDate();
  }
}
