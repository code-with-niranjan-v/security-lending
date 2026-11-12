package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;

import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import java.time.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {
  private final LocateRequestRepository locates;
  private final ReservationRepository reservations;
  private final BorrowerConnectionRepository connections;
  private final AgreementService agreements;
  private final TenantService tenants;
  private final InventoryService inventory;
  private final EventService events;
  private final Clock clock;

  public LocateRequest decideLocate(
      long org, long id, long quantity, boolean approve, String reason) {
    tenants.lock(org);
    var l = found(locates.findByIdAndOrganizationId(id, org));
    require("PENDING".equals(l.getStatus()), "Locate already decided");
    var c = tenants.connection(org, l.getConnectionId());
    if (approve) {
      agreements.active(org, l.getMslaId());
      require(l.getExpiresAt().isAfter(clock.instant()), "Locate has expired");
      require(
          quantity > 0
              && quantity <= l.getQuantity()
              && quantity <= inventory.available(inventory.position(org, l.getSecurityId())),
          "Approved locate exceeds requested/current available quantity");
      l.setApprovedQuantity(quantity);
      l.setStatus(quantity == l.getQuantity() ? "APPROVED" : "PARTIALLY_APPROVED");
      events.confirm(
          c,
          "LOCATE_APPROVED",
          "locate_request",
          l.getBorrowerLocateId(),
          Map.of("approvedQuantity", quantity, "reason", reason));
    } else {
      l.setStatus("REJECTED");
      events.confirm(
          c,
          "LOCATE_REJECTED",
          "locate_request",
          l.getBorrowerLocateId(),
          Map.of("reason", reason));
    }
    events.audit(org, approve ? "LOCATE_DECISION_APPROVED" : "LOCATE_DECISION_REJECTED", String.valueOf(id));
    return l;
  }

  public Reservation decide(long org, long id, long quantity, boolean approve, String reason) {
    tenants.lock(org);
    var r = found(reservations.findByIdAndOrganizationId(id, org));
    require("REQUESTED".equals(r.getStatus()), "Reservation already decided");
    var c = tenants.connection(org, r.getConnectionId());
    if (approve) {
      agreements.active(org, r.getMslaId());
      var l = found(locates.findById(r.getLocateId()));
      require(r.getExpiresAt().isAfter(clock.instant()), "Reservation request has expired");
      require(
          quantity > 0 && quantity <= r.getQuantity() && quantity <= l.getApprovedQuantity(),
          "Invalid reservation quantity");
      var p = inventory.position(org, r.getSecurityId());
      require(inventory.available(p) >= quantity, "Insufficient available inventory");
      p.setReservedQuantity(p.getReservedQuantity() + quantity);
      r.setQuantity(quantity);
      r.setRemainingQuantity(quantity);
      r.setStatus("ACTIVE");
      r.setRevision(r.getRevision() + 1);
      inventory.movement(p, "INVENTORY_RESERVED", quantity, "reservation:" + id);
      events.confirm(
          c,
          "RESERVATION_CONFIRMED",
          "reservation",
          r.getBorrowerReservationId(),
          Map.of("quantity", quantity));
      events.lifecycle(
          org,
          "REGISTER_RESERVATION_EXPIRY",
          id,
          Map.of(
              "expiresAt",
              r.getExpiresAt(),
              "revision",
              r.getRevision(),
              "connectionId",
              c.getId()));
    } else {
      r.setStatus("REJECTED");
      events.confirm(
          c,
          "RESERVATION_REJECTED",
          "reservation",
          r.getBorrowerReservationId(),
          Map.of("reason", reason));
    }
    return r;
  }

  public void release(long org, Reservation r, boolean expiry) {
    require(r.getOrganizationId() == org, "Reservation organization mismatch");
    if ("RELEASED".equals(r.getStatus()) || "EXPIRED".equals(r.getStatus())) return;
    require(
        "ACTIVE".equals(r.getStatus()) || "CONSUMED".equals(r.getStatus()),
        "Reservation is not releasable");
    long quantity = r.getRemainingQuantity();
    var p = inventory.position(org, r.getSecurityId());
    require(p.getReservedQuantity() >= quantity, "Reservation inventory mismatch");
    p.setReservedQuantity(p.getReservedQuantity() - quantity);
    r.setRemainingQuantity(0L);
    r.setStatus(expiry ? "EXPIRED" : "RELEASED");
    r.setRevision(r.getRevision() + 1);
    inventory.movement(
        p,
        expiry ? "RESERVATION_EXPIRED" : "RESERVATION_RELEASED",
        -quantity,
        "reservation:" + r.getId());
    events.confirm(
        found(connections.findById(r.getConnectionId())),
        expiry ? "RESERVATION_EXPIRED" : "RESERVATION_RELEASED",
        "reservation",
        r.getBorrowerReservationId(),
        Map.of());
    events.local(org, "RESERVATION_CLOSED", r.getId(), Map.of("reservation", r));
  }

  public void expire(long org, long id, Instant expectedExpiry) {
    tenants.lock(org);
    var r = found(reservations.findByIdAndOrganizationId(id, org));
    require(r.getExpiresAt().equals(expectedExpiry), "Stale reservation expiry event");
    require(!clock.instant().isBefore(r.getExpiresAt()), "Reservation deadline has not arrived");
    release(org, r, true);
  }

  public void restoreAfterRejection(Loan loan) {
    var r = found(reservations.findById(loan.getReservationId()));
    var p = inventory.position(loan.getOrganizationId(), loan.getSecurityId());
    if ("ACTIVE".equals(r.getStatus()) || "CONSUMED".equals(r.getStatus())) {
      r.setRemainingQuantity(r.getRemainingQuantity() + loan.getQuantity());
      r.setStatus("ACTIVE");
      r.setRevision(r.getRevision() + 1);
      events.confirm(
          found(connections.findById(r.getConnectionId())),
          "RESERVATION_UPDATED",
          "reservation",
          r.getBorrowerReservationId(),
          Map.of("remainingQuantity", r.getRemainingQuantity(), "status", r.getStatus()));
    } else {
      p.setReservedQuantity(p.getReservedQuantity() - loan.getQuantity());
      inventory.movement(
          p, "REJECTED_LOAN_RESERVATION_RELEASED", -loan.getQuantity(), "loan:" + loan.getId());
    }
  }
}
