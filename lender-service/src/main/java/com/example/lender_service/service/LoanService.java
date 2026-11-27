package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;

import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class LoanService {
  private final LoanRepository loans;
  private final LoanReturnRepository returns;
  private final CollateralPositionRepository positions;
  private final BorrowerConnectionRepository connections;
  private final AgreementService agreements;
  private final CollateralService collateral;
  private final ReservationService reservations;
  private final TenantService tenants;
  private final InventoryService inventory;
  private final MarketService market;
  private final EventService events;
  private final RecallService recalls;
  private final MarginCallService marginCalls;
  private final Clock clock;

  public BigDecimal exposure(long mslaId) {
    BigDecimal value = BigDecimal.ZERO;
    for (var l : loans.findByMslaId(mslaId))
      if (Set.of("APPROVED_PENDING_SETTLEMENT", "ACTIVE", "DEFAULTED").contains(l.getStatus()))
        value =
            value.add(
                market
                    .price(l.getSecurityId())
                    .value()
                    .multiply(BigDecimal.valueOf(l.getOutstandingQuantity())));
    return amount(value);
  }

  public Loan decide(long org, long id, boolean approve, String reason) {
    tenants.lock(org);
    var l = found(loans.findByIdAndOrganizationId(id, org));
    require("PENDING_REVIEW".equals(l.getStatus()), "Loan already decided");
    if (approve) {
      var a = agreements.active(org, l.getMslaId());
      BigDecimal requested =
          market.price(l.getSecurityId()).value().multiply(BigDecimal.valueOf(l.getQuantity()));
      require(
          exposure(a.getId()).add(requested).compareTo(a.getCreditLimit()) <= 0,
          "MSLA credit limit exceeded");
      l.setStatus("APPROVED_PENDING_SETTLEMENT");
    } else {
      for (var p : positions.findByLoanId(id))
        require(
            !Set.of("POSTED", "RELEASE_REQUESTED").contains(p.getStatus()),
            "Release confirmed collateral before rejecting the loan");
      for (var p : positions.findByLoanId(id))
        if ("PLEDGE_PENDING".equals(p.getStatus())) collateral.reject(org, p.getId(), reason);
      l.setStatus("REJECTED");
      l.setOutstandingQuantity(0L);
      reservations.restoreAfterRejection(l);
      events.confirm(
          found(connections.findById(l.getConnectionId())),
          "LOAN_REJECTED",
          "loan",
          l.getBorrowerLoanId(),
          Map.of("reason", reason));
    }
    events.local(org, "LOAN_DECIDED", id, Map.of("loan", l, "reason", reason));
    return l;
  }

  public Loan settle(long org, long id, String reference) {
    tenants.lock(org);
    var l = found(loans.findByIdAndOrganizationId(id, org));
    if ("ACTIVE".equals(l.getStatus())) {
      require(
          reference.equals(l.getSettlementReference()),
          "Loan already settled with a different reference");
      return l;
    }
    require(
        "APPROVED_PENDING_SETTLEMENT".equals(l.getStatus()),
        "Loan must be approved before settlement");
    var a = agreements.active(org, l.getMslaId());
    require(
        exposure(a.getId()).compareTo(a.getCreditLimit()) <= 0,
        "MSLA credit limit exceeded at settlement");
    collateral.requireInitialCoverage(org, id);
    var p = inventory.position(org, l.getSecurityId());
    require(p.getReservedQuantity() >= l.getQuantity(), "Loan has no corresponding reserved stock");
    p.setReservedQuantity(p.getReservedQuantity() - l.getQuantity());
    p.setLoanedQuantity(p.getLoanedQuantity() + l.getQuantity());
    l.setStatus("ACTIVE");
    l.setSettlementReference(reference);
    l.setSettledAt(clock.instant());
    inventory.movement(p, "LOAN_SETTLED", l.getQuantity(), reference);
    events.confirm(
        found(connections.findById(l.getConnectionId())),
        "LOAN_SETTLED",
        "loan",
        l.getBorrowerLoanId(),
        Map.of());
    events.local(
        org,
        "LOAN_SETTLED",
        id,
        Map.of("loan", l, "agreement", a, "collateral", positions.findByLoanId(id)));
    return l;
  }

  public LoanReturn returnDecision(long org, long id, boolean settle, String reference) {
    tenants.lock(org);
    var r = found(returns.findByIdAndOrganizationId(id, org));
    if ("SETTLED".equals(r.getStatus())) {
      require(settle && reference.equals(r.getSettlementReference()), "Return already settled");
      return r;
    }
    require("PENDING".equals(r.getStatus()), "Return already decided");
    var l = found(loans.findById(r.getLoanId()));
    require("ACTIVE".equals(l.getStatus()), "Loan is not active");
    if (settle) {
      require(r.getQuantity() <= l.getOutstandingQuantity(), "Return exceeds outstanding loan");
      l.setOutstandingQuantity(l.getOutstandingQuantity() - r.getQuantity());
      if (l.getOutstandingQuantity() == 0) {
        l.setStatus("CLOSED");
        l.setClosedAt(clock.instant());
        for (var p : positions.findByLoanId(l.getId())) {
          if (Set.of("POSTED", "RELEASE_REQUESTED").contains(p.getStatus())) {
            if ("POSTED".equals(p.getStatus())) {
              p.setStatus("RELEASE_REQUESTED");
              events.local(org, "COLLATERAL_RELEASE_REQUESTED", p.getId(), Map.of("position", p));
            }
            collateral.release(org, p.getId(), reference, true);
          }
        }
        // Lifecycle owns end-of-loan accrual and rebate settlement. Queue it only after the loan
        // has been closed in this transaction, so it can issue any final bill and rebate payment.
        events.lifecycle(org, "LOAN_CLOSED", l.getId(), Map.of("loanId", l.getId()));
      }
      var p = inventory.position(org, l.getSecurityId());
      p.setLoanedQuantity(p.getLoanedQuantity() - r.getQuantity());
      r.setStatus("SETTLED");
      r.setSettledAt(clock.instant());
      r.setSettlementReference(reference);
      inventory.movement(p, "LOAN_RETURN_SETTLED", -r.getQuantity(), reference);
    } else r.setStatus("REJECTED");
    events.confirm(
        found(connections.findById(l.getConnectionId())),
        settle ? "RETURN_SETTLED" : "RETURN_REJECTED",
        "loan_return",
        r.getBorrowerReturnId(),
        Map.of("reference", reference));
    if (settle) recalls.applySettlement(l, r);
    if (settle) marginCalls.applyExposureReduction(l, r);
    events.local(
        org,
        settle ? "LOAN_RETURN_SETTLED" : "LOAN_RETURN_REJECTED",
        id,
        Map.of("loan", l, "return", r));
    return r;
  }
}
