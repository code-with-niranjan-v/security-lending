package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;

import com.example.lender_service.dto.LifecycleEvent;
import com.example.lender_service.dto.RecallRequest;
import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Read projections and explicit owner instructions. No timers, accrual formulas, margin creation or
 * buy-in execution.
 */
@Service
@RequiredArgsConstructor
public class LifecycleEventService {
  private final TenantService tenants;
  private final InboxEventRepository inbox;
  private final LifecycleCursorRepository cursors;
  private final LoanRepository loans;
  private final BorrowerConnectionRepository connections;
  private final ReservationService reservations;
  private final LocateRequestRepository locates;
  private final LifecycleRequestRepository requests;
  private final LoanValuationRepository valuations;
  private final MarginCallRepository calls;
  private final RecallRepository recalls;
  private final RecallService recallWorkflow;
  private final CollateralService collateralPreview;
  private final LendingFeeRepository fees;
  private final RebateRepository rebates;
  private final DailyAccrualRepository accruals;
  private final LoanDefaultRepository defaults;
  private final BuyInRepository buyIns;
  private final RebatePaymentRepository rebatePayments;
  private final LenderAccountRepository accounts;
  private final LenderAccountLedgerRepository ledger;
  private final EventService events;
  private final JsonService json;
  private final Clock clock;

  @Transactional
  public void receive(String body) {
    var e = json.parse(body, LifecycleEvent.class);
    tenants.lock(e.organizationId());
    String hash = JsonService.hash("LIFECYCLE|" + body);
    var old = inbox.findById(e.eventId());
    if (old.isPresent()) {
      require(
          hash.equals(old.get().getPayloadHash()),
          "Lifecycle event ID reused with different content");
      return;
    }
    require(e.data().isObject(), "Lifecycle data must be an object");
    LifecycleCursor cursor = null;
    if (Set.of("LOAN_STATUS_UPDATED", "LIFECYCLE_REQUEST_ACCEPTED", "LIFECYCLE_REQUEST_REJECTED")
        .contains(e.type())) {
      String resource = e.type().startsWith("LIFECYCLE_REQUEST_") ? "LIFECYCLE_REQUEST" : e.type();
      cursor =
          cursors
              .findByOrganizationIdAndResourceAndReferenceId(
                  e.organizationId(), resource, e.referenceId())
              .orElse(null);
      if (cursor != null && cursor.getSourceVersion() >= e.version()) {
        remember(e, hash);
        return;
      }
      if (cursor == null) {
        cursor = new LifecycleCursor();
        cursor.setOrganizationId(e.organizationId());
        cursor.setResource(resource);
        cursor.setReferenceId(e.referenceId());
      }
    }

    switch (e.type()) {
      case "RESERVATION_EXPIRED" ->
          reservations.expire(
              e.organizationId(),
              e.referenceId(),
              Instant.parse(text(e.data(), "expectedExpiresAt")));
      case "LOCATE_EXPIRED" -> {
        var l = found(locates.findByIdAndOrganizationId(e.referenceId(), e.organizationId()));
        require(!clock.instant().isBefore(l.getExpiresAt()), "Locate deadline has not arrived");
        if (Set.of("PENDING", "APPROVED", "PARTIALLY_APPROVED").contains(l.getStatus())) {
          l.setStatus("EXPIRED");
          events.audit(e.organizationId(), "LOCATE_EXPIRY_CONFIRMED", String.valueOf(l.getId()));
          events.confirm(
              found(connections.findById(l.getConnectionId())),
              "LOCATE_EXPIRED",
              "locate_request",
              l.getBorrowerLocateId(),
              Map.of());
        }
      }
      case "RECALL_DEADLINE_REACHED" ->
          recallWorkflow.deadlineReached(
              e.organizationId(), e.referenceId(), instant(e.data(), "expectedDueAt"));
      case "LIFECYCLE_REQUEST_ACCEPTED", "LIFECYCLE_REQUEST_REJECTED" -> {
        var r = found(requests.findByIdAndOrganizationId(e.referenceId(), e.organizationId()));
        require(
            Set.of("PENDING", "ACCEPTED").contains(r.getStatus()),
            "Lifecycle request already completed");
        r.setStatus(e.type().endsWith("ACCEPTED") ? "ACCEPTED" : "REJECTED");
        if ("VALUATION_REQUESTED".equals(r.getType()) && e.type().endsWith("REJECTED")) {
          var payload = new LinkedHashMap<>(json.map(json.tree(r.getPayload())));
          payload.put("failureReason", text(e.data(), "reason"));
          r.setPayload(json.write(payload));
        }
      }
      case "LOAN_STATUS_UPDATED" -> {
        var l = found(loans.findByIdAndOrganizationId(e.referenceId(), e.organizationId()));
        String status = text(e.data(), "status");
        require(
            status.equals("DEFAULTED") && Set.of("ACTIVE", "DEFAULTED").contains(l.getStatus()),
            "Only an explicit default status change is accepted here; recovery requires settlement"
                + " integration");
        l.setStatus(status);
        events.confirm(
            found(connections.findById(l.getConnectionId())),
            "LOAN_STATUS_UPDATED",
            "loan",
            l.getBorrowerLoanId(),
            Map.of("status", status, "outstandingQuantity", l.getOutstandingQuantity()));
      }
      default -> projection(e);
    }
    if (cursor != null) {
      cursor.setSourceVersion(e.version());
      cursors.save(cursor);
    }
    remember(e, hash);
    events.local(
        e.organizationId(),
        "LIFECYCLE_VIEW_UPDATED",
        e.referenceId(),
        Map.of("sourceEventId", e.eventId(), "sourceType", e.type()));
  }

  private void remember(LifecycleEvent e, String hash) {
    var receipt = new InboxEvent();
    receipt.setEventId(e.eventId());
    receipt.setOrganizationId(e.organizationId());
    receipt.setPayloadHash(hash);
    receipt.setSource("LIFECYCLE");
    receipt.setCreatedAt(clock.instant());
    inbox.save(receipt);
  }

  private <T extends LifecycleProjection> T view(
      LifecycleEvent e, Loan loan, Optional<T> previous, Supplier<T> factory) {
    var p = previous.orElseGet(factory);
    if (p.getId() != null) {
      require(p.getLoanId().equals(loan.getId()), "Owner reference belongs to another loan");
      if (p.getSourceVersion() >= e.version()) return null;
    } else {
      p.setOrganizationId(e.organizationId());
      p.setConnectionId(loan.getConnectionId());
      p.setLoanId(loan.getId());
      p.setOwnerReferenceId(e.referenceId());
      p.setCreatedAt(e.occurredAt());
    }
    p.setSourceVersion(e.version());
    p.setSourceData(json.write(e.data()));
    p.setStatus(e.data().path("status").asText("REPORTED"));
    return p;
  }

  private void projection(LifecycleEvent e) {
    var d = e.data();
    var loan = found(loans.findByIdAndOrganizationId(number(d, "loanId"), e.organizationId()));
    var connection = found(connections.findById(loan.getConnectionId()));
    boolean forward = true;
    String resource = e.type();
    switch (e.type()) {
      case "VALUATION_REPORTED" -> {
        if (d.hasNonNull("lifecycleRequestId")) {
          var request =
              found(
                  requests.findByIdAndOrganizationId(
                      number(d, "lifecycleRequestId"), e.organizationId()));
          require(
              "VALUATION_REQUESTED".equals(request.getType())
                  && Objects.equals(request.getLoanId(), loan.getId())
                  && Objects.equals(request.getConnectionId(), loan.getConnectionId()),
              "Valuation request does not match loan");
          if (!Set.of("PENDING", "ACCEPTED").contains(request.getStatus())) return;
        }
        var p =
            view(
                e,
                loan,
                valuations.findByOrganizationIdAndOwnerReferenceId(
                    e.organizationId(), e.referenceId()),
                LoanValuation::new);
        if (p == null) {
          if (!d.hasNonNull("lifecycleRequestId")) return;
          var persisted =
              found(
                  valuations.findByOrganizationIdAndOwnerReferenceId(
                      e.organizationId(), e.referenceId()));
          require(
              persisted.getSourceVersion().equals(e.version())
                  && json.tree(persisted.getSourceData()).equals(d),
              "Stored valuation does not match owner report");
          completeRequest(e, loan);
          break;
        }
        p.setLoanMarketValue(decimal(d, "loanMarketValue"));
        p.setRequiredCollateral(decimal(d, "requiredCollateral"));
        p.setRecognizedCollateral(decimal(d, "recognizedCollateral"));
        p.setCoverageRatio(decimal(d, "coverageRatio"));
        p.setShortfall(decimal(d, "shortfall"));
        p.setValuedAt(e.occurredAt());
        valuations.save(p);
        completeRequest(e, loan);
      }
      case "MARGIN_CALL_UPDATED" -> {
        if(d.hasNonNull("lifecycleRequestId")){
          var request =
              found(
                  requests.findByIdAndOrganizationId(
                      number(d, "lifecycleRequestId"), e.organizationId()));
          require(
              "MARGIN_CALL_REQUESTED".equals(request.getType())
                  && Objects.equals(request.getLoanId(), loan.getId())
                  && Objects.equals(request.getConnectionId(), connection.getId()),
              "Margin request does not match this loan");
          if (!Set.of("PENDING", "ACCEPTED").contains(request.getStatus())) return;
          var terms = json.tree(request.getPayload());
          require(
              decimal(d, "amount").compareTo(decimal(terms, "amount")) == 0
                  && instant(d, "dueAt").equals(instant(terms, "dueAt"))
                  && text(d, "reason").equals(text(terms, "reason"))
                  && decimal(d, "fulfilledAmount").signum() == 0
                  && "OPEN".equals(text(d, "status")),
              "Margin issuance does not match requested terms");
          boolean open =
              calls
                  .findByOrganizationIdAndLoanIdIn(e.organizationId(), List.of(loan.getId()))
                  .stream()
                  .anyMatch(
                      x ->
                          Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "OVERDUE")
                              .contains(x.getStatus()));
          var currentShortfall =
              (BigDecimal)
                  collateralPreview
                      .preview(e.organizationId(), loan.getId(), false, null)
                      .get("shortfall");
          if (open
              || !"ACTIVE".equals(loan.getStatus())
              || !instant(d, "dueAt").isAfter(clock.instant())
              || currentShortfall.signum() <= 0
              || decimal(d, "amount").compareTo(currentShortfall) < 0) {
            request.setStatus("REJECTED");
            return;
          }
        }
        var p =
            view(
                e,
                loan,
                calls.findByOrganizationIdAndOwnerReferenceId(e.organizationId(), e.referenceId()),
                MarginCall::new);
        if (p == null) return;
        p.setAmount(decimal(d, "amount"));
        p.setFulfilledAmount(decimal(d, "fulfilledAmount"));
        require(
            p.getFulfilledAmount().compareTo(p.getAmount()) <= 0,
            "Margin fulfillment exceeds amount");
        p.setStatus(obligationStatus(d));
        p.setDueAt(instant(d, "dueAt"));
        p.setReason(text(d, "reason"));
        text(d, "externalId");
        calls.save(p);
        completeRequest(e, loan);
      }
      case "RECALL_UPDATED" -> {
        var existing =
            recalls.findByOrganizationIdAndOwnerReferenceId(e.organizationId(), e.referenceId());
        if (d.hasNonNull("lifecycleRequestId")) {
          var request =
              found(
                  requests.findByIdAndOrganizationId(
                      number(d, "lifecycleRequestId"), e.organizationId()));
          require(
              "RECALL_REQUESTED".equals(request.getType())
                  && Objects.equals(request.getLoanId(), loan.getId())
                  && Objects.equals(request.getConnectionId(), connection.getId()),
              "Recall request does not match this loan");
          if (!Set.of("PENDING", "ACCEPTED").contains(request.getStatus())) return;
          long quantity = number(d, "quantity");
          require(
              quantity == json.parse(request.getPayload(), RecallRequest.class).quantity()
                  && number(d, "fulfilledQuantity") == 0
                  && "OPEN".equals(text(d, "status")),
              "Recall issuance does not match the requested terms");
          if (existing.isPresent()
              || !"ACTIVE".equals(loan.getStatus())
              || quantity
                  > loan.getOutstandingQuantity() - recallWorkflow.outstanding(loan.getId())) {
            request.setStatus("REJECTED");
            events.local(
                e.organizationId(),
                "RECALL_REQUEST_REJECTED",
                request.getId(),
                Map.of(
                    "reason", "Recall reference or outstanding quantity is no longer available"));
            return;
          }
        }
        var p = view(e, loan, existing, Recall::new);
        if (p == null) return;
        p.setQuantity(number(d, "quantity"));
        p.setFulfilledQuantity(number(d, "fulfilledQuantity"));
        require(
            p.getQuantity() > 0
                && p.getFulfilledQuantity() >= 0
                && p.getFulfilledQuantity() <= p.getQuantity(),
            "Invalid recalled quantity");
        p.setStatus(obligationStatus(d));
        p.setDueAt(instant(d, "dueAt"));
        p.setReason(text(d, "reason"));
        text(d, "externalId");
        recalls.save(p);
        completeRequest(e, loan);
      }
      case "FEE_BILL_ISSUED" -> {
        var existing =
            fees.findByOrganizationIdAndOwnerReferenceId(e.organizationId(), e.referenceId());
        if (existing.isPresent()) {
          if (existing.get().getSourceVersion() >= e.version()) return;
          throw new IllegalArgumentException(
              "Bill amendments require an explicit correction contract");
        }
        var p = view(e, loan, existing, LendingFee::new);
        p.setAmount(decimal(d, "amount"));
        p.setRate(decimal(d, "rate"));
        p.setPeriodStart(date(d, "periodStart"));
        p.setPeriodEnd(date(d, "periodEnd"));
        p.setDueDate(date(d, "dueDate"));
        p.setStatus("DUE");
        fees.save(p);
      }
      case "ACCRUAL_REPORTED" -> {
        var old =
            accruals.findByOrganizationIdAndOwnerReferenceId(e.organizationId(), e.referenceId());
        if (old.isPresent()) {
          require(
              old.get().getSourceVersion() >= e.version(),
              "Accrual amendments require a correction contract");
          return;
        }
        var p = view(e, loan, old, DailyAccrual::new);
        p.setAmount(decimal(d, "amount"));
        p.setRate(decimal(d, "rate"));
        p.setBaseValue(decimal(d, "baseValue"));
        p.setEntryDate(date(d, "date"));
        String type = text(d, "type");
        require(Set.of("FEE", "REBATE").contains(type), "Invalid accrual type");
        p.setAccrualType(type);
        accruals.save(p);
      }
      case "REBATE_ACCRUED" -> {
        var old =
            rebates.findByOrganizationIdAndOwnerReferenceId(e.organizationId(), e.referenceId());
        if (old.isPresent()) {
          require(
              old.get().getSourceVersion() >= e.version(),
              "Rebate amendments require a correction contract");
          return;
        }
        var p = view(e, loan, old, Rebate::new);
        p.setAmount(decimal(d, "amount"));
        p.setRate(decimal(d, "rate"));
        p.setAccrualDate(date(d, "date"));
        p.setPaymentDate(date(d, "paymentDate"));
        if (d.hasNonNull("periodStart")) {
          p.setPeriodStart(date(d, "periodStart"));
          p.setPeriodEnd(date(d, "periodEnd"));
          p.setSettlementFrequency(text(d, "settlementFrequency"));
        }
        p.setStatus("ACCRUED");
        rebates.save(p);
        resource = "rebate";
      }
      case "REBATE_PAID" -> {
        var p =
            found(
                rebates.findByOrganizationIdAndOwnerReferenceId(
                    e.organizationId(), e.referenceId()));
        if (p.getSourceVersion() >= e.version()) return;
        require(
            p.getLoanId().equals(loan.getId())
                && "ACCRUED".equals(p.getStatus())
                && decimal(d, "amount").compareTo(p.getAmount()) == 0,
            "Rebate payment does not match reported accrual");
        p.setSourceVersion(e.version());
        p.setStatus("PAID");
        var payment = new RebatePayment();
        payment.setOrganizationId(e.organizationId());
        payment.setConnectionId(connection.getId());
        payment.setLoanId(loan.getId());
        payment.setRebateId(p.getId());
        payment.setAmount(p.getAmount());
        payment.setSourceEventId(e.eventId());
        payment.setCreatedAt(e.occurredAt());
        rebatePayments.save(payment);
        var account = found(accounts.findByOrganizationId(e.organizationId()));
        account.setRebatePaid(account.getRebatePaid().add(p.getAmount()));
        ledger(e, "REBATE_PAID", p.getAmount());
        completeRequest(e, loan);
        resource = "rebate";
      }
      case "FEE_PAYMENT_RECEIVED" -> {
        var p =
            found(
                fees.findByOrganizationIdAndOwnerReferenceId(e.organizationId(), e.referenceId()));
        if (p.getSourceVersion() >= e.version()) return;
        require(
            p.getLoanId().equals(loan.getId())
                && "DUE".equals(p.getStatus())
                && decimal(d, "amount").compareTo(p.getAmount()) == 0,
            "Fee payment does not match bill");
        p.setSourceVersion(e.version());
        p.setStatus("PAID");
        var account = found(accounts.findByOrganizationId(e.organizationId()));
        account.setFeeReceived(account.getFeeReceived().add(p.getAmount()));
        ledger(e, "FEE_PAYMENT_RECEIVED", p.getAmount());
        events.confirm(
            connection,
            "PAYMENT_CONFIRMED",
            "payment_request",
            number(d, "borrowerPaymentRequestId"),
            Map.of("amount", p.getAmount()));
        forward = false;
      }
      case "DEFAULT_REPORTED" -> {
        var p =
            view(
                e,
                loan,
                defaults.findByOrganizationIdAndOwnerReferenceId(
                    e.organizationId(), e.referenceId()),
                LoanDefault::new);
        if (p == null) return;
        p.setObligationId(number(d, "obligationId"));
        p.setObligationType(text(d, "obligationType"));
        p.setReason(text(d, "reason"));
        defaults.save(p);
      }
      case "BUY_IN_REPORTED" -> {
        var p =
            view(
                e,
                loan,
                buyIns.findByOrganizationIdAndOwnerReferenceId(e.organizationId(), e.referenceId()),
                BuyIn::new);
        if (p == null) return;
        p.setDefaultReferenceId(number(d, "defaultId"));
        p.setQuantity(number(d, "quantity"));
        p.setPrice(decimal(d, "price"));
        p.setCosts(decimal(d, "costs"));
        p.setCollateralApplied(decimal(d, "collateralApplied"));
        p.setResidualBalance(decimal(d, "residualBalance"));
        buyIns.save(p);
        completeRequest(e, loan);
      }
      default -> throw new IllegalArgumentException("Unsupported lifecycle event " + e.type());
    }
    if (forward) {
      var data = new LinkedHashMap<>(json.map(d));
      data.put("loanId", loan.getBorrowerLoanId());
      events.confirm(connection, e.type(), resource, e.referenceId(), data);
    }
  }

  private void completeRequest(LifecycleEvent e, Loan loan) {
    if (e.data().hasNonNull("lifecycleRequestId")) {
      var r =
          found(
              requests.findByIdAndOrganizationId(
                  number(e.data(), "lifecycleRequestId"), e.organizationId()));
      require(Objects.equals(r.getLoanId(), loan.getId()), "Lifecycle request loan mismatch");
      r.setStatus("COMPLETED");
    }
  }

  private void ledger(LifecycleEvent e, String type, BigDecimal amount) {
    var l = new LenderAccountLedger();
    l.setOrganizationId(e.organizationId());
    l.setType(type);
    l.setAmount(amount);
    l.setReference(e.eventId());
    l.setCreatedAt(e.occurredAt());
    ledger.save(l);
  }

  private static String text(JsonNode d, String key) {
    var value = d.get(key);
    if (value == null || value.isNull() || value.asText().isBlank())
      throw new IllegalArgumentException("Missing field " + key);
    return value.asText();
  }

  private static long number(JsonNode d, String key) {
    return new BigDecimal(text(d, key)).longValueExact();
  }

  private static BigDecimal decimal(JsonNode d, String key) {
    var value = new BigDecimal(text(d, key));
    require(value.signum() >= 0, "Negative amount/rate " + key);
    return value;
  }

  private static LocalDate date(JsonNode d, String key) {
    return LocalDate.parse(text(d, key));
  }

  private static Instant instant(JsonNode d, String key) {
    return Instant.parse(text(d, key));
  }

  private static String obligationStatus(JsonNode d) {
    String status = text(d, "status");
    require(
        Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "FULFILLED", "OVERDUE", "CANCELLED")
            .contains(status),
        "Invalid obligation status");
    return status;
  }
}
