package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.found;
import static com.example.lender_service.service.Checks.require;

import com.example.lender_service.dto.SettlementRequest;
import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarginCallService {

  private final MarginCallRepository calls;
  private final LoanRepository loans;
  private final BorrowerConnectionRepository connections;
  private final EventService events;
  private final CollateralPositionRepository positions;
  private final MarketService market;
  private final TenantService tenants;
  private final MslaRepository agreements;

  @Transactional
  public Map<String, Object> confirm(
    long org,
    long id,
    SettlementRequest request
  ) {
    tenants.lock(org);
    var call = found(calls.findByIdAndOrganizationId(id, org));
    var loan = found(loans.findByIdAndOrganizationId(call.getLoanId(), org));
    publish(call, loan, request.reference());
    return Map.of(
      "status",
      "CONFIRMED",
      "marginCallId",
      id,
      "loanId",
      loan.getBorrowerLoanId(),
      "reference",
      request.reference()
    );
  }

  public List<MarginCall> pendingDeliveryRepair() {
    return calls.findByStatusIn(
      Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "OVERDUE")
    );
  }

  @Transactional
  public void repairMissingDelivery(long org, long id) {
    tenants.lock(org);
    var call = found(calls.findByIdAndOrganizationId(id, org));
    if (
      !Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "OVERDUE").contains(
        call.getStatus()
      )
    ) return;
    if (
      Objects.equals(
        call.getBorrowerDeliverySourceVersion(),
        call.getSourceVersion()
      )
    ) return;
    publish(
      call,
      found(loans.findByIdAndOrganizationId(call.getLoanId(), org)),
      "repair-missing-margin-delivery"
    );
  }

  @Transactional
  public MarginCall cancel(long org, long id, SettlementRequest request) {
    tenants.lock(org);
    var call = found(calls.findByIdAndOrganizationId(id, org));
    if (
      Set.of("CANCELLED", "CANCELLATION_PENDING").contains(call.getStatus())
    ) return call;
    require(
      Set.of("PENDING", "OPEN", "OVERDUE").contains(call.getStatus()) &&
        call.getFulfilledAmount().signum() == 0,
      "Only an unfulfilled open margin call can be cancelled"
    );
    require(
      !hasSettlement(call),
      "Collateral has already been submitted for this margin call"
    );
    call.setStatus("CANCELLATION_PENDING");
    call.setCancellationReference(request.reference());
    call.setCancellationFailureReason(null);
    publish(
      call,
      found(loans.findByIdAndOrganizationId(call.getLoanId(), org)),
      request.reference()
    );
    events.audit(org, "MARGIN_CALL_CANCELLATION_REQUESTED", String.valueOf(id));
    return call;
  }

  private boolean hasSettlement(MarginCall call) {
    return positions
      .findByLoanId(call.getLoanId())
      .stream()
      .anyMatch(
        p ->
          Objects.equals(p.getMarginCallId(), call.getOwnerReferenceId()) &&
          Objects.equals(p.getConnectionId(), call.getConnectionId()) &&
          !p.getCreatedAt().isBefore(call.getCreatedAt()) &&
          !Set.of("REJECTED", "FAILED", "CANCELLED").contains(p.getStatus())
      );
  }

  /** Borrower verification is serialized with collateral submission in the borrower database. */
  public void cancellationDecision(
    BorrowerConnection connection,
    long borrowerLoanId,
    long ownerReference,
    String reference,
    boolean accepted,
    String reason
  ) {
    var call = found(
      calls.findByOrganizationIdAndOwnerReferenceId(
        connection.getOrganizationId(),
        ownerReference
      )
    );
    var loan = found(
      loans.findByIdAndOrganizationId(
        call.getLoanId(),
        connection.getOrganizationId()
      )
    );
    require(
      call.getConnectionId().equals(connection.getId()) &&
        loan.getBorrowerLoanId() == borrowerLoanId,
      "Cancellation does not belong to this borrower loan"
    );
    if (
      !"CANCELLATION_PENDING".equals(call.getStatus()) ||
      !Objects.equals(reference, call.getCancellationReference())
    ) return;
    require(
      !accepted ||
        (call.getFulfilledAmount().signum() == 0 && !hasSettlement(call)),
      "Cannot cancel a margin call with submitted collateral"
    );
    call.setStatus(
      accepted
        ? "CANCELLED"
        : call.getDueAt().isBefore(java.time.Instant.now())
          ? "OVERDUE"
          : "OPEN"
    );
    call.setCancellationFailureReason(
      accepted ? null : reason.substring(0, Math.min(500, reason.length()))
    );
    // Fence any owner snapshot queued before this decision.
    call.setSourceVersion(call.getSourceVersion() + 1);
    if (!accepted) positions
      .findByLoanId(loan.getId())
      .stream()
      .filter(
        p ->
          "POSTED".equals(p.getStatus()) &&
          Objects.equals(p.getMarginCallId(), ownerReference)
      )
      .forEach(p -> applyCollateralReceipt(p, loan));
    publish(call, loan, reference);
    events.audit(
      connection.getOrganizationId(),
      accepted ? "MARGIN_CALL_CANCELLED" : "MARGIN_CALL_CANCELLATION_REJECTED",
      String.valueOf(call.getId())
    );
  }

  /** Called with the lender organization locked, in the collateral settlement transaction. */
  void applyCollateralReceipt(CollateralPosition position, Loan loan) {
    if (position.getMarginCallId() == null) return;
    // The borrower stores the owner reference as its margin-call ID.
    var call = found(
      calls.findByOrganizationIdAndOwnerReferenceId(
        position.getOrganizationId(),
        position.getMarginCallId()
      )
    );
    require(
      Objects.equals(call.getLoanId(), loan.getId()) &&
        Objects.equals(call.getConnectionId(), position.getConnectionId()),
      "Margin call does not belong to this loan and borrower connection"
    );
    if (
      !Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "OVERDUE").contains(
        call.getStatus()
      ) ||
      position.getCreatedAt().isBefore(call.getCreatedAt())
    ) return;

    // Reconcile posted positions so retries and receipts recorded before this change
    // count once. Earlier cycles on the same loan are excluded.
    BigDecimal recognized = positions
      .findByLoanId(loan.getId())
      .stream()
      .filter(
        p ->
          Objects.equals(p.getMarginCallId(), call.getOwnerReferenceId()) &&
          Objects.equals(p.getConnectionId(), call.getConnectionId()) &&
          Set.of("POSTED", "RELEASE_REQUESTED").contains(p.getStatus()) &&
          !p.getCreatedAt().isBefore(call.getCreatedAt())
      )
      .map(this::recognizedValue)
      .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal fulfilled = recognized
      .max(call.getFulfilledAmount())
      .min(call.getAmount());
    if (fulfilled.signum() == 0) return;
    String status =
      fulfilled.compareTo(call.getAmount()) == 0
        ? "FULFILLED"
        : "PARTIALLY_FULFILLED";
    if (
      fulfilled.compareTo(call.getFulfilledAmount()) == 0 &&
      status.equals(call.getStatus())
    ) return;
    call.setFulfilledAmount(fulfilled);
    call.setStatus(status);
    publish(call, loan, position.getSettlementReference());
  }

  /** Apply the coverage released by a lender-settled return to its originating margin call. */
  void applyExposureReduction(Loan loan, LoanReturn returned) {
    if (returned.getMarginCallId() == null) return;
    var call = found(calls.findByIdAndOrganizationId(returned.getMarginCallId(), loan.getOrganizationId()));
    require(call.getLoanId().equals(loan.getId())
        && call.getConnectionId().equals(loan.getConnectionId()),
        "Exposure-reduction return does not match its margin call");
    if (!Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "OVERDUE").contains(call.getStatus())) return;
    var agreement = found(agreements.findByIdAndOrganizationId(loan.getMslaId(), loan.getOrganizationId()));
    BigDecimal released = market.price(loan.getSecurityId()).value()
        .multiply(BigDecimal.valueOf(returned.getQuantity()))
        .multiply(agreement.getCoverageRatio());
    BigDecimal fulfilled = call.getFulfilledAmount().add(released).min(call.getAmount());
    if (fulfilled.compareTo(call.getFulfilledAmount()) <= 0) return;
    call.setFulfilledAmount(fulfilled);
    call.setStatus(fulfilled.compareTo(call.getAmount()) >= 0 ? "FULFILLED" : "PARTIALLY_FULFILLED");
    publish(call, loan, returned.getSettlementReference());
  }

  private BigDecimal recognizedValue(CollateralPosition position) {
    BigDecimal value = "CASH".equals(position.getAssetType())
      ? position.getCashAmount()
      : market
          .price(position.getSecurityId())
          .value()
          .multiply(BigDecimal.valueOf(position.getQuantity()));
    return Checks.amount(
      value.multiply(BigDecimal.ONE.subtract(position.getHaircut()))
    );
  }

  private void publish(MarginCall call, Loan loan, String reference) {
    var connection = found(connections.findById(loan.getConnectionId()));
    var data = new LinkedHashMap<String, Object>();
    data.put("loanId", loan.getBorrowerLoanId());
    data.put("externalId", String.valueOf(call.getOwnerReferenceId()));
    data.put("amount", call.getAmount().toPlainString());
    data.put("fulfilledAmount", call.getFulfilledAmount().toPlainString());
    data.put("status", call.getStatus());
    data.put("dueAt", call.getDueAt().toString());
    data.put("reason", call.getReason());
    data.put("issuedAt", call.getCreatedAt().toString());
    data.put("reference", reference);
    if ("CANCELLATION_PENDING".equals(call.getStatus())) {
      data.put("cancellationRequested", true);
      data.put("status", "OPEN");
      data.put("cancellationReference", call.getCancellationReference());
    }

    call.setBorrowerDeliverySourceVersion(call.getSourceVersion());
    events.confirm(
      connection,
      "MARGIN_CALL_UPDATED",
      "MARGIN_CALL_UPDATED",
      call.getOwnerReferenceId(),
      data
    );
  }
}
