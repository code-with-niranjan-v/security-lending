package com.example.lifecycle_service.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.lifecycle_service.dto.*;
import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AccrualRunServiceTest {
  @Mock LoanRepository loans;
  @Mock RecallRequestRecordRepository requests;
  @Mock LenderOutboxEventRepository outbox;
  @Mock LendingFeeService fees;
  @Mock RebateLifecycleService rebates;
  @Mock PlatformTransactionManager transactions;
  ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  AccrualRunService service;

  @BeforeEach
  void setup() {
    service = new AccrualRunService(loans, requests, outbox, fees, rebates, json, transactions);
    ReflectionTestUtils.setField(service, "topic", "lender.lifecycle.events.v1");
    when(transactions.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
  }

  void request(String type) {
    var r = mock(RecallRequestRecord.class);
    when(r.getType()).thenReturn(type);
    when(r.getStatus()).thenReturn("PENDING");
    when(r.getLoanId()).thenReturn(7L);
    when(r.getConnectionId()).thenReturn(3L);
    when(requests.findByIdAndOrganizationId(42L, 1L)).thenReturn(Optional.of(r));
    when(loans.lockOrganization(1L)).thenReturn(mock(Organization.class));
    var l = new Loan();
    l.setId(7L);
    l.setConnectionId(3L);
    l.setStatus("ACTIVE");
    l.setSettledAt(java.time.Instant.now().minusSeconds(60));
    when(loans.findByIdAndOrganizationId(7L, 1L)).thenReturn(Optional.of(l));
  }

  @Test
  void completedFeeRunReportsActualCountsOnceOnRedelivery() throws Exception {
    request("FEE_ACCRUAL_REQUESTED");
    when(outbox.existsByEventId("accrual-run-42")).thenReturn(false, true);
    when(fees.process(7))
        .thenReturn(
            new LendingFeeRunResult(7, LocalDate.of(2026, 11, 12), 2, 1, new BigDecimal("4.5")));
    service.process(1, 42);
    service.process(1, 42);
    verify(fees).process(7);
    var capture = ArgumentCaptor.forClass(LenderOutboxEvent.class);
    verify(outbox).save(capture.capture());
    var report = json.readTree(capture.getValue().getPayload());
    assertEquals("ACCRUAL_RUN_COMPLETED", report.path("type").asText());
    assertEquals(2, report.path("data").path("result").path("daysRecorded").asInt());
    assertEquals(1, report.path("data").path("result").path("billsQueued").asInt());
    assertEquals(
        "2026-11-12", report.path("data").path("result").path("processedThrough").asText());
    verify(transactions, never()).rollback(any());
  }

  @Test
  void failedComputationRollsBackBeforeCommittingFailureReport() throws Exception {
    request("REBATE_ACCRUAL_REQUESTED");
    when(rebates.process(7))
        .thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Market data unavailable"));
    service.process(1, 42);
    verify(transactions).rollback(any());
    verify(transactions).commit(any());
    var capture = ArgumentCaptor.forClass(LenderOutboxEvent.class);
    verify(outbox).save(capture.capture());
    var report = json.readTree(capture.getValue().getPayload());
    assertEquals("LIFECYCLE_REQUEST_REJECTED", report.path("type").asText());
    assertEquals("Market data unavailable", report.path("data").path("reason").asText());
  }
}
