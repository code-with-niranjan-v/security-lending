package com.example.lifecycle_service.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MarginRequestLifecycleServiceTest {
  @Test
  void ownerQueuesConfirmedTermsOnceAndRejectsAnExistingOpenCall() throws Exception {
    var loans = mock(LoanRepository.class);
    var requests = mock(RecallRequestRecordRepository.class);
    var calls = mock(MarginCallRepository.class);
    var outbox = mock(LenderOutboxEventRepository.class);
    var json = new ObjectMapper();
    var service = new MarginRequestLifecycleService(loans, requests, calls, outbox, json);
    ReflectionTestUtils.setField(service, "topic", "lender.lifecycle.events.v1");
    var loan = new Loan();
    loan.setId(7L);
    loan.setConnectionId(3L);
    loan.setStatus("ACTIVE");
    var request = mock(RecallRequestRecord.class);
    when(request.getType()).thenReturn("MARGIN_CALL_REQUESTED");
    when(request.getStatus()).thenReturn("PENDING");
    when(request.getLoanId()).thenReturn(7L);
    when(request.getConnectionId()).thenReturn(3L);
    var due = Instant.now().plusSeconds(86400);
    when(request.getPayload())
        .thenReturn("{\"amount\":1600,\"reason\":\"Market move\",\"dueAt\":\"" + due + "\"}");
    when(loans.lockOrganization(1L)).thenReturn(mock(Organization.class));
    when(requests.findByIdAndOrganizationId(42L, 1L)).thenReturn(Optional.of(request));
    when(loans.findByIdAndOrganizationId(7L, 1L)).thenReturn(Optional.of(loan));
    when(outbox.existsByEventId("margin-request-42")).thenReturn(false, true, false);
    service.issue(1, 42);
    service.issue(1, 42);
    var capture = ArgumentCaptor.forClass(LenderOutboxEvent.class);
    verify(outbox).save(capture.capture());
    var event = json.readTree(capture.getValue().getPayload());
    assertEquals("MARGIN_CALL_UPDATED", event.path("type").asText());
    assertEquals(7, event.path("referenceId").asLong());
    assertEquals(42, event.path("data").path("lifecycleRequestId").asLong());
    assertEquals(1600, event.path("data").path("amount").asInt());
    assertEquals(due, Instant.parse(event.path("data").path("dueAt").asText()));
    var open = new MarginCall();
    open.setStatus("OPEN");
    when(calls.findByLoanId(7L)).thenReturn(List.of(open));
    service.issue(1, 42);
    verify(outbox, times(2)).save(capture.capture());
    assertEquals(
        "LIFECYCLE_REQUEST_REJECTED",
        json.readTree(capture.getValue().getPayload()).path("type").asText());
  }
}
