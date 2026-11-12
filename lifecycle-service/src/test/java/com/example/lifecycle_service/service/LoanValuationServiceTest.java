package com.example.lifecycle_service.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LoanValuationServiceTest {
  @Mock LoanRepository loans;
  @Mock MslaRepository agreements;
  @Mock FeeSecurityRepository securities;
  @Mock CollateralPositionRepository positions;
  @Mock LoanValuationRepository valuations;
  @Mock LenderOutboxEventRepository outbox;
  @Mock RecallRequestRecordRepository requests;
  LoanValuationService service;
  ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  @BeforeEach
  void setup() {
    service =
        new LoanValuationService(
            loans, agreements, securities, positions, valuations, outbox, requests, json);
    ReflectionTestUtils.setField(service, "eventTopic", "lender.lifecycle.events.v1");
    ReflectionTestUtils.setField(service, "commandTopic", "lender.lifecycle.commands.v1");
  }

  FeeSecurity security(long id, String price) throws Exception {
    var ctor = FeeSecurity.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    var s = ctor.newInstance();
    ReflectionTestUtils.setField(s, "id", id);
    ReflectionTestUtils.setField(s, "symbol", "EQ" + id);
    ReflectionTestUtils.setField(s, "currency", "INR");
    ReflectionTestUtils.setField(s, "price", price == null ? null : new BigDecimal(price));
    ReflectionTestUtils.setField(s, "priceAt", Instant.now().minusSeconds(60));
    return s;
  }

  void loan() throws Exception {
    var l = new Loan();
    l.setId(9L);
    l.setOrganizationId(2L);
    l.setConnectionId(11L);
    l.setMslaId(4L);
    l.setSecurityId(5L);
    l.setOutstandingQuantity(10L);
    l.setStatus("ACTIVE");
    when(loans.lockOrganization(2L)).thenReturn(mock(Organization.class));
    when(loans.findByIdAndOrganizationId(9L, 2L)).thenReturn(Optional.of(l));
    var a = new Msla();
    a.setOrganizationId(2L);
    a.setCoverageRatio(new BigDecimal("1.05"));
    when(agreements.findById(4L)).thenReturn(Optional.of(a));
    when(securities.findById(5L)).thenReturn(Optional.of(security(5, "100")));
  }

  CollateralPosition position(long id, String type, String status) {
    var p = new CollateralPosition();
    p.setId(id);
    p.setOrganizationId(2L);
    p.setConnectionId(11L);
    p.setLoanId(9L);
    p.setAssetType(type);
    p.setStatus(status);
    p.setQuantity(3L);
    p.setCashAmount(new BigDecimal("100"));
    p.setHaircut(new BigDecimal("0.1"));
    p.setSecurityId(6L);
    return p;
  }

  @Test
  void repeatedRunsUseCollateralOwnPriceAndRetainSnapshots() throws Exception {
    loan();
    when(loans.findOrganizationId(9L)).thenReturn(Optional.of(2L));
    when(securities.findById(6L)).thenReturn(Optional.of(security(6, "200")));
    when(positions.findByLoanId(9L))
        .thenReturn(
            List.of(
                position(1, "CASH", "POSTED"),
                position(2, "SECURITY", "RELEASE_REQUESTED"),
                position(3, "CASH", "PLEDGE_PENDING")));
    var first = service.runValuationForLoan(9);
    when(valuations.findFirstByLoanIdOrderByValuedAtDescIdDesc(9L)).thenReturn(Optional.of(first));
    var second = service.runValuationForLoan(9);
    assertEquals(new BigDecimal("1000.000000"), first.getLoanMarketValue());
    assertEquals(new BigDecimal("630.000000"), first.getRecognizedCollateral());
    assertEquals(new BigDecimal("420.000000"), first.getShortfall());
    assertNotEquals(first.getOwnerReferenceId(), second.getOwnerReferenceId());
    assertEquals(2, second.getSourceVersion());
    verify(valuations, times(2)).save(any());
    verify(outbox, times(2)).save(any());
    var marks = json.readTree(first.getSourceData()).path("collateral");
    assertEquals(2, marks.size());
    assertEquals(200, marks.get(1).path("price").asInt());
  }

  @Test
  void missingPriceNeverCreatesAZeroValuation() throws Exception {
    loan();
    when(loans.findOrganizationId(9L)).thenReturn(Optional.of(2L));
    when(securities.findById(5L)).thenReturn(Optional.of(security(5, null)));
    assertThrows(ResponseStatusException.class, () -> service.runValuationForLoan(9));
    verify(valuations, never()).save(any());
    verify(outbox, never()).save(any());
  }

  RecallRequestRecord request() {
    var r = mock(RecallRequestRecord.class);
    when(requests.findByIdAndOrganizationId(42L, 2L)).thenReturn(Optional.of(r));
    when(r.getType()).thenReturn("VALUATION_REQUESTED");
    when(r.getStatus()).thenReturn("PENDING");
    when(r.getLoanId()).thenReturn(9L);
    when(r.getConnectionId()).thenReturn(11L);
    return r;
  }

  @Test
  void requestedRunQueuesOneDurableReportOnRedelivery() throws Exception {
    loan();
    request();
    when(outbox.existsByEventId("valuation-run-42")).thenReturn(false, true);
    service.processRequest(2, 42);
    service.processRequest(2, 42);
    var captured = ArgumentCaptor.forClass(LenderOutboxEvent.class);
    verify(outbox).save(captured.capture());
    var e = json.readTree(captured.getValue().getPayload());
    assertEquals("VALUATION_REPORTED", e.path("type").asText());
    assertEquals(42, e.path("data").path("lifecycleRequestId").asLong());
    verify(valuations).save(any());
  }

  @Test
  void missingCollateralQuoteReportsFailureWithoutPartialSnapshot() throws Exception {
    loan();
    when(positions.findByLoanId(9L)).thenReturn(List.of(position(2, "SECURITY", "POSTED")));
    when(securities.findById(6L)).thenReturn(Optional.empty());
    request();
    service.processRequest(2, 42);
    var captured = ArgumentCaptor.forClass(LenderOutboxEvent.class);
    verify(outbox).save(captured.capture());
    assertEquals(
        "LIFECYCLE_REQUEST_REJECTED",
        json.readTree(captured.getValue().getPayload()).path("type").asText());
    verify(valuations, never()).save(any());
  }
}
