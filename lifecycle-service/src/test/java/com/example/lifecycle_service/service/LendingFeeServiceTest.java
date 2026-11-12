package com.example.lifecycle_service.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
class LendingFeeServiceTest {
  @Mock LoanRepository loans;
  @Mock MslaRepository agreements;
  @Mock FeeLoanReturnRepository returns;
  @Mock FeeSecurityRepository securities;
  @Mock FeeMarketPrices market;
  @Mock LendingFeeDayRepository days;
  @Mock LenderOutboxEventRepository outbox;
  @Mock FeeSecurity security;
  @Mock Clock clock;
  private final ObjectMapper json = new ObjectMapper();
  private final Loan loan = new Loan();
  private final Msla agreement = new Msla();
  private final List<LendingFeeDay> records = new ArrayList<>();
  private final List<JsonNode> events = new ArrayList<>();
  private LendingFeeService fees;

  @BeforeEach
  void setup() {
    fees = new LendingFeeService(loans, agreements, returns, securities, market, days, outbox, json, clock);
    ReflectionTestUtils.setField(fees, "topic", "lender.lifecycle.events.v1");
    when(clock.instant()).thenReturn(Instant.parse("2026-11-02T10:00:00Z"));
    loan.setId(7L);
    loan.setOrganizationId(1L);
    loan.setMslaId(5L);
    loan.setSecurityId(15L);
    loan.setQuantity(100L);
    loan.setOutstandingQuantity(60L);
    loan.setSettledAt(Instant.parse("2026-10-30T06:00:00Z"));
    agreement.setFeeRate(new BigDecimal("0.0365"));
    agreement.setDayBasis(365);
    when(loans.findOrganizationId(7)).thenReturn(Optional.of(1L));
    when(loans.findByIdAndOrganizationId(7L, 1L)).thenReturn(Optional.of(loan));
    when(agreements.findById(5L)).thenReturn(Optional.of(agreement));
    when(securities.findById(15)).thenReturn(Optional.of(security));
    when(days.save(any(LendingFeeDay.class))).thenAnswer(call -> {
      LendingFeeDay row = call.getArgument(0);
      records.add(row);
      ReflectionTestUtils.setField(row, "id", 2_000_000_000_000L + records.size());
      return row;
    });
    when(days.findByLoanIdAndAccrualDate(eq(7L), any())).thenAnswer(call -> records.stream()
        .filter(row -> row.getAccrualDate().equals(call.getArgument(1))).findFirst());
    when(outbox.save(any(LenderOutboxEvent.class))).thenAnswer(call -> {
      LenderOutboxEvent event = call.getArgument(0);
      events.add(json.readTree(event.getPayload()));
      return event;
    });
  }

  @ParameterizedTest
  @CsvSource({"MONTHLY,1,2.2", "AT_CLOSE,0,0"})
  void calculatesHistoricalFeesAndBillsOnSchedule(String frequency, int count, String amount) {
    agreement.setBillingFrequency(frequency);
    var partial = mock(FeeLoanReturn.class);
    when(partial.getQuantity()).thenReturn(40L);
    when(partial.getSettledAt()).thenReturn(Instant.parse("2026-10-31T07:00:00Z"));
    when(returns.findByLoanIdAndStatusAndSettledAtBeforeOrderBySettledAtAscIdAsc(eq(7L), eq("SETTLED"), any()))
        .thenReturn(List.of(partial));
    var marks = new TreeMap<Instant, BigDecimal>();
    marks.put(Instant.parse("2026-10-30T08:00:00Z"), new BigDecimal("100"));
    marks.put(Instant.parse("2026-10-31T08:00:00Z"), new BigDecimal("200"));
    when(market.history(eq(security), any(), eq(false))).thenReturn(marks);
    when(days.findByLoanIdAndBilledFalseOrderByAccrualDateAsc(7))
        .thenAnswer(call -> records.stream().filter(row -> !row.isBilled()).toList());

    var first = fees.process(7);
    assertEquals(4, first.daysRecorded());
    assertEquals(count, first.billsQueued());
    assertEquals(0, new BigDecimal(amount).compareTo(first.billedAmount()));
    assertEquals(List.of("1.000000", "1.200000", "1.200000", "1.200000"),
        records.stream().map(row -> row.getAmount().toPlainString()).toList());
    assertEquals("FEE", events.get(0).path("data").path("type").asText());
    assertEquals(0, fees.process(7).daysRecorded());

    loan.setClosedAt(Instant.parse("2026-11-02T09:00:00Z"));
    var finalRun = fees.process(7);
    assertEquals(0, finalRun.daysRecorded());
    assertEquals(1, finalRun.billsQueued());
    assertEquals(0, new BigDecimal("4.6").compareTo(first.billedAmount().add(finalRun.billedAmount())));
    JsonNode lastEvent = events.get(events.size() - 1);
    assertEquals("FEE_BILL_ISSUED", lastEvent.path("type").asText());
    assertEquals("2026-11-02", lastEvent.path("data").path("dueDate").asText());
    assertEquals(0, fees.process(7).billsQueued());
  }

  @Test
  void dueProcessingIncludesTodayWhenTheLoopIsRunForCurrentDate() {
    loan.setSettledAt(Instant.parse("2026-11-02T06:00:00Z"));
    agreement.setBillingFrequency("MONTHLY");
    var marks = new TreeMap<Instant, BigDecimal>();
    marks.put(Instant.parse("2026-11-02T09:00:00Z"), new BigDecimal("200"));
    when(market.history(eq(security), any(), eq(false))).thenReturn(marks);
    when(days.findByLoanIdAndBilledFalseOrderByAccrualDateAsc(7L))
        .thenReturn(List.of());

    var result = fees.process(7);

    assertEquals(LocalDate.parse("2026-11-02"), result.processedThrough());
    assertEquals(1, result.daysRecorded());
    assertEquals(0, result.billsQueued());
    assertEquals(BigDecimal.ZERO, result.billedAmount());
  }

  @Test
  void demoUsesCurrentQuantityAndDoesNotAccrueTwice() {
    var marks = new TreeMap<Instant, BigDecimal>();
    marks.put(Instant.parse("2026-11-02T09:00:00Z"), new BigDecimal("200"));
    when(market.history(eq(security), any(), eq(true))).thenReturn(marks);
    var result = fees.accrueToday(7);
    assertTrue(result.created());
    assertEquals(new BigDecimal("12000.000000"), result.marketValue());
    assertEquals(new BigDecimal("1.200000"), result.amount());
    assertFalse(fees.accrueToday(7).created());
    assertEquals(1, events.size());
    assertNull(loan.getFeeProcessedThrough());
  }

  @Test
  void sameDayDemoAccrualIsBilledWhenTheLoanCloses() {
    loan.setSettledAt(Instant.parse("2026-11-02T06:00:00Z"));
    agreement.setBillingFrequency("MONTHLY");
    var marks = new TreeMap<Instant, BigDecimal>();
    marks.put(Instant.parse("2026-11-02T09:00:00Z"), new BigDecimal("200"));
    when(market.history(eq(security), any(), eq(true))).thenReturn(marks);
    when(days.findByLoanIdAndBilledFalseOrderByAccrualDateAsc(7L))
        .thenAnswer(call -> records.stream().filter(row -> !row.isBilled()).toList());

    fees.accrueToday(7);
    loan.setFeeProcessedThrough(LocalDate.parse("2026-11-02"));
    loan.setClosedAt(Instant.parse("2026-11-02T10:00:00Z"));
    loan.setFeeFinalized(true);

    var closed = fees.process(7);

    assertEquals(0, closed.daysRecorded());
    assertEquals(1, closed.billsQueued());
    assertTrue(records.get(0).isBilled());
    assertEquals("FEE_BILL_ISSUED", events.get(events.size() - 1).path("type").asText());
  }
}
