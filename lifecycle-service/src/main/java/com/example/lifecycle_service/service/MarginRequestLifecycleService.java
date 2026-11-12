package com.example.lifecycle_service.service;

import com.example.lifecycle_service.model.LenderOutboxEvent;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner confirmation of lender requests. The lender applies the versioned projection. */
@Service
@RequiredArgsConstructor
public class MarginRequestLifecycleService {
  private final LoanRepository loans;
  private final RecallRequestRecordRepository requests;
  private final MarginCallRepository calls;
  private final LenderOutboxEventRepository outbox;
  private final ObjectMapper json;

  @Value("${lifecycle.lender-events-topic:lender.lifecycle.events.v1}")
  private String topic;

  @Transactional
  public void issue(long org, long id) throws JsonProcessingException {
    if (loans.lockOrganization(org) == null)
      throw new IllegalArgumentException("Unknown organization");
    var request = requests.findByIdAndOrganizationId(id, org).orElseThrow();
    if (!"MARGIN_CALL_REQUESTED".equals(request.getType()))
      throw new IllegalArgumentException("Not a margin request");
    String eventId = "margin-request-" + id;
    if (!Set.of("PENDING", "ACCEPTED").contains(request.getStatus())
        || outbox.existsByEventId(eventId)) return;
    var loan = loans.findByIdAndOrganizationId(request.getLoanId(), org).orElse(null);
    var data = json.readTree(request.getPayload());
    BigDecimal amount = data.path("amount").decimalValue();
    Instant due = Instant.parse(data.path("dueAt").asText()), now = Instant.now();
    boolean open =
        loan != null
            && calls.findByLoanId(loan.getId()).stream()
                .anyMatch(
                    x ->
                        Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "OVERDUE")
                            .contains(x.getStatus()));
    boolean valid =
        loan != null
            && "ACTIVE".equals(loan.getStatus())
            && request.getConnectionId().equals(loan.getConnectionId())
            && amount.signum() > 0
            && due.isAfter(now)
            && !open;
    // Automatic coverage calls use the loan ID as owner reference. Reuse that reference
    // with a higher version to preserve the existing one-call-per-loan convention.
    var existing =
        loan == null
            ? null
            : calls
                .findFirstByOrganizationIdAndOwnerReferenceIdOrderByCreatedAtDesc(org, loan.getId())
                .orElse(null);
    long version = existing == null ? 1 : existing.getSourceVersion() + 1;
    Map<String, Object> body =
        valid
            ? Map.of(
                "loanId",
                loan.getId(),
                "lifecycleRequestId",
                id,
                "externalId",
                "MARGIN-" + id,
                "amount",
                amount,
                "fulfilledAmount",
                BigDecimal.ZERO,
                "dueAt",
                due.toString(),
                "reason",
                data.path("reason").asText(),
                "status",
                "OPEN")
            : Map.of("reason", "Loan, deadline or duplicate margin call check failed");
    outbox.save(
        new LenderOutboxEvent(
            org,
            eventId,
            topic,
            json.writeValueAsString(
                Map.of(
                    "eventId",
                    eventId,
                    "type",
                    valid ? "MARGIN_CALL_UPDATED" : "LIFECYCLE_REQUEST_REJECTED",
                    "organizationId",
                    org,
                    "referenceId",
                    valid ? loan.getId() : id,
                    "version",
                    version,
                    "occurredAt",
                    now.toString(),
                    "data",
                    body)),
            now));
  }
}
