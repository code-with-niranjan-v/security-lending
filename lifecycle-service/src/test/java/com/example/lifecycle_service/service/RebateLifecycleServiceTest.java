package com.example.lifecycle_service.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.lifecycle_service.dto.CashCollateralChange;
import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RebateLifecycleServiceTest {
  @Mock LoanRepository loans;
  @Mock MslaRepository agreements;
  @Mock CashCollateralMovementRepository collateral;
  @Mock RebateDayRepository days;
  @Mock LenderOutboxEventRepository outbox;
  @Mock Clock clock;

  private final ObjectMapper json = new ObjectMapper();
  private final Loan loan = new Loan();
  private final Msla agreement = new Msla();
  private final List<RebateDay> daily = new ArrayList<>();
  private final List<JsonNode> events = new ArrayList<>();
  private RebateLifecycleService rebates;

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(Instant.parse("2026-11-02T10:00:00Z"));
    rebates = new RebateLifecycleService(loans, agreements, collateral, days, outbox, json, clock);
    ReflectionTestUtils.setField(rebates, "topic", "lender.lifecycle.events.v1");
    loan.setId(7L);
    loan.setOrganizationId(1L);
    loan.setMslaId(5L);
    loan.setSettledAt(Instant.parse("2026-10-30T06:00:00Z"));
    agreement.setRebateRate(new BigDecimal("0.10"));
    agreement.setDayBasis(365);
    when(loans.findOrganizationId(7L)).thenReturn(Optional.of(1L));
    when(loans.findByIdAndOrganizationId(7L, 1L)).thenReturn(Optional.of(loan));
    when(agreements.findById(5L)).thenReturn(Optional.of(agreement));
    var changes = List.of(
        change("2026-10-30T04:00:00Z", "PLEDGE_CONFIRMED"),
        change("2026-10-31T04:00:00Z", "PLEDGE_CONFIRMED"),
        change("2026-11-01T04:00:00Z", "RELEASE_CONFIRMED"),
        change("2026-11-02T10:30:00Z", "RELEASE_CONFIRMED"));
    when(collateral.cashChangesBefore(eq(7L), any())).thenAnswer(call -> {
      Instant before = call.getArgument(1);
      return changes.stream().filter(change -> change.recordedAt().isBefore(before)).toList();
    });
    when(days.save(any(RebateDay.class))).thenAnswer(call -> {
      RebateDay row = call.getArgument(0);
      daily.add(row);
      ReflectionTestUtils.setField(row, "id", 1_000_000_000_000L + daily.size());
      return row;
    });
    when(days.findByLoanIdAndAccrualDate(eq(7L), any(LocalDate.class)))
        .thenAnswer(call -> daily.stream()
            .filter(row -> row.getAccrualDate().equals(call.getArgument(1)))
            .findFirst());
    when(days.findByLoanIdAndSettlementReferenceIdIsNullOrderByAccrualDateAsc(7L))
        .thenAnswer(call -> daily.stream().filter(row -> row.getSettlementReferenceId() == null).toList());
    when(outbox.save(any(LenderOutboxEvent.class))).thenAnswer(call -> {
      LenderOutboxEvent event = call.getArgument(0);
      events.add(json.readTree(event.getPayload()));
      return event;
    });
  }

  @ParameterizedTest
  @CsvSource({"DAILY,3,40", "WEEKLY,1,40", "MONTHLY,1,30", "AT_CLOSE,0,0"})
  void dailyBalancesAreIndependentOfSettlementSchedule(String frequency, int count, String amount) {
    agreement.setRebateSettlementFrequency(frequency);
    var result = rebates.process(7);
    assertEquals(LocalDate.parse("2026-11-02"), result.processedThrough());
    assertEquals(4, result.daysRecorded());
    assertEquals(count, result.settlementsQueued());
    assertEquals(0, new BigDecimal(amount).compareTo(result.queuedSettlementAmount()));
    assertEquals(List.of(new BigDecimal("10.000000"), new BigDecimal("20.000000"), new BigDecimal("10.000000"), new BigDecimal("10.000000")),
        daily.stream().map(RebateDay::getAmount).toList());
    assertEquals(4 + count * 2, events.size());
    assertEquals(0, rebates.process(7).daysRecorded());
    assertEquals(4 + count * 2, events.size());
  }

  @Test
  void defaultScheduleSettlesOnceOnClosureUsingCashHeldAtTermination() {
    assertEquals("AT_CLOSE", agreement.getRebateSettlementFrequency());
    var prior = rebates.process(7);
    assertEquals(0, prior.settlementsQueued());
    assertEquals(4, prior.daysRecorded());
    loan.setClosedAt(Instant.parse("2026-11-02T08:00:00Z"));
    var closed = rebates.process(7);
    assertEquals(0, closed.daysRecorded());
    assertEquals(1, closed.settlementsQueued());
    assertEquals(0, new BigDecimal("50").compareTo(closed.queuedSettlementAmount()));
    var bill = events.get(events.size() - 2);
    var payment = events.get(events.size() - 1);
    assertEquals("REBATE_ACCRUED", bill.path("type").asText());
    assertEquals("REBATE_PAID", payment.path("type").asText());
    assertEquals(bill.path("referenceId"), payment.path("referenceId"));
    assertEquals("2026-10-30", bill.path("data").path("periodStart").asText());
    assertEquals("2026-11-02", bill.path("data").path("periodEnd").asText());
    assertEquals(0, rebates.process(7).settlementsQueued());
  }

  @Test
  void currentDayAccruesButDailyPaymentWaitsUntilDueDate() {
    loan.setSettledAt(Instant.parse("2026-11-02T08:00:00Z"));
    agreement.setRebateSettlementFrequency("DAILY");
    var result = rebates.process(7);
    assertEquals(LocalDate.parse("2026-11-02"), result.processedThrough());
    assertEquals(1, result.daysRecorded());
    assertEquals(0, result.settlementsQueued());
    assertEquals(BigDecimal.ZERO, result.queuedSettlementAmount());
  }

  @Test
  void demoAccrualRecordsTodaysCashOnceAndKeepsTheNormalSettlementSchedule() {
    loan.setSettledAt(Instant.parse("2026-11-02T08:00:00Z"));
    agreement.setRebateSettlementFrequency("DAILY");
    var prior = rebates.process(7);
    assertEquals(LocalDate.parse("2026-11-02"), prior.processedThrough());
    assertEquals(1, prior.daysRecorded());
    assertEquals(0, prior.settlementsQueued());
    var today = rebates.accrueToday(7);
    assertFalse(today.created());
    assertEquals(List.of("ACCRUAL_REPORTED"), events.stream().map(e -> e.path("type").asText()).toList());

    Instant closedAt = Instant.parse("2026-11-02T10:15:00Z");
    when(clock.instant()).thenReturn(closedAt);
    loan.setClosedAt(closedAt);
    var closed = rebates.process(7);
    assertEquals(0, closed.daysRecorded());
    assertEquals(1, closed.settlementsQueued());
    assertEquals(0, rebates.process(7).settlementsQueued());
  }

  @Test
  void sameDayDemoAccrualSettlesAtCloseDespiteTheProcessedCursor() {
    loan.setSettledAt(Instant.parse("2026-11-02T08:00:00Z"));
    agreement.setRebateSettlementFrequency("AT_CLOSE");
    rebates.accrueToday(7);
    loan.setRebateProcessedThrough(LocalDate.parse("2026-11-02"));
    loan.setClosedAt(Instant.parse("2026-11-02T10:00:00Z"));

    var closed = rebates.process(7);

    assertEquals(0, closed.daysRecorded());
    assertEquals(1, closed.settlementsQueued());
    assertEquals(List.of("ACCRUAL_REPORTED", "REBATE_ACCRUED", "REBATE_PAID"),
        events.stream().map(event -> event.path("type").asText()).toList());
  }

  private CashCollateralChange change(String at, String type) {
    return new CashCollateralChange(Instant.parse(at), type, new BigDecimal("36500"));
  }
}
