package com.example.lifecycle_service.service;

import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Computation and completion report commit together. A failed calculation rolls back before its
 * failure report.
 */
@Service
@RequiredArgsConstructor
public class AccrualRunService {
  private final LoanRepository loans;
  private final RecallRequestRecordRepository requests;
  private final LenderOutboxEventRepository outbox;
  private final LendingFeeService fees;
  private final RebateLifecycleService rebates;
  private final ObjectMapper json;
  private final PlatformTransactionManager transactions;

  @Value("${lifecycle.lender-events-topic:lender.lifecycle.events.v1}")
  private String topic;

  private static final Set<String> TYPES =
      Set.of("FEE_ACCRUAL_REQUESTED", "REBATE_ACCRUAL_REQUESTED");

  public void process(long org, long requestId) {
    var tx = new TransactionTemplate(transactions);
    try {
      tx.executeWithoutResult(
          status -> {
            var r = locked(org, requestId);
            if (r == null) return;
            var loan = loans.findByIdAndOrganizationId(r.getLoanId(), org).orElseThrow();
            if (!Objects.equals(loan.getConnectionId(), r.getConnectionId())
                || loan.getSettledAt() == null
                || !Set.of("ACTIVE", "CLOSED").contains(loan.getStatus()))
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "Accrual requires a settled active or closed loan");
            Object result =
                "FEE_ACCRUAL_REQUESTED".equals(r.getType())
                    ? fees.process(loan.getId())
                    : rebates.process(loan.getId());
            var details = json.convertValue(result, new TypeReference<Map<String, Object>>() {});
            java.time.LocalDate through =
                result instanceof com.example.lifecycle_service.dto.LendingFeeRunResult fee
                    ? fee.processedThrough()
                    : ((com.example.lifecycle_service.dto.RebateRunResult) result)
                        .processedThrough();
            details.put("processedThrough", through == null ? null : through.toString());
            report(
                org,
                requestId,
                "ACCRUAL_RUN_COMPLETED",
                Map.of(
                    "loanId",
                    loan.getId(),
                    "kind",
                    r.getType().startsWith("FEE") ? "FEE" : "REBATE",
                    "result",
                    details));
          });
    } catch (ResponseStatusException e) {
      tx.executeWithoutResult(
          status -> {
            if (locked(org, requestId) != null)
              report(
                  org,
                  requestId,
                  "LIFECYCLE_REQUEST_REJECTED",
                  Map.of("reason", Objects.requireNonNullElse(e.getReason(), "Accrual failed")));
          });
    }
  }

  private RecallRequestRecord locked(long org, long id) {
    if (loans.lockOrganization(org) == null)
      throw new IllegalArgumentException("Unknown organization");
    var r = requests.findByIdAndOrganizationId(id, org).orElseThrow();
    if (!TYPES.contains(r.getType())) throw new IllegalArgumentException("Not an accrual request");
    if (!Set.of("PENDING", "ACCEPTED").contains(r.getStatus())
        || outbox.existsByEventId("accrual-run-" + id)) return null;
    return r;
  }

  private void report(long org, long id, String type, Object data) {
    Instant now = Instant.now();
    String eventId = "accrual-run-" + id;
    try {
      outbox.save(
          new LenderOutboxEvent(
              org,
              eventId,
              topic,
              json.writeValueAsString(
                  Map.of(
                      "eventId",
                      eventId,
                      "organizationId",
                      org,
                      "referenceId",
                      id,
                      "type",
                      type,
                      "version",
                      1,
                      "occurredAt",
                      now.toString(),
                      "data",
                      data)),
              now));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
