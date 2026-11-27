package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;

import com.example.lender_service.dto.*;
import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Receives borrower intent; approvals and actual settlement are separate lender operations. */
@Service
@RequiredArgsConstructor
public class BorrowerCommandService {
  private final OrganizationRepository organizations;
  private final BorrowerConnectionRepository connections;
  private final InboxEventRepository inbox;
  private final MslaRepository agreements;
  private final CollateralHaircutRepository rules;
  private final AssetRepository assets;
  private final LocateRequestRepository locates;
  private final ReservationRepository reservations;
  private final LoanRepository loans;
  private final CollateralPositionRepository positions;
  private final LoanReturnRepository returns;
  private final LendingFeeRepository fees;
  private final LenderAccountRepository accounts;
  private final LenderAccountLedgerRepository ledger;
  private final SecurityMasterRepository securities;
  private final TenantService tenants;
  private final AgreementService agreementService;
  private final ReservationService reservationService;
  private final CollateralService collateral;
  private final EventService events;
  private final RecallService recalls;
  private final MarginCallService marginCalls;
  private final MarginCallRepository marginCallsRepository;
  private final JsonService json;
  private final Clock clock;
  private final ReturnCancellationRepository returnCancellations;

  @Transactional
  public void receive(String body, String counterpartyId) {
    var command = json.parse(body, BorrowerCommand.class);
    if (counterpartyId == null || counterpartyId.isBlank())
      throw new IllegalArgumentException("Missing trusted x-counterparty-id header");
    require(
        "COUNTERPARTY".equals(command.destination()), "Only counterparty commands are accepted");
    var organization = found(organizations.findByExternalId(command.lenderExternalId()));
    tenants.lock(organization.getId());
    var connection =
        found(
            connections.findByOrganizationIdAndExternalIdAndBorrowerOrganizationId(
                organization.getId(), counterpartyId, command.organizationId()));
    require(connection.getActive(), "Borrower connection is inactive");
    String hash = JsonService.hash(counterpartyId + "|" + body);
    var old = inbox.findById(command.eventId());
    if (old.isPresent()) {
      require(
          old.get().getPayloadHash().equals(hash),
          "Event ID reused with changed payload or sender");
      return;
    }
    switch (command.type()) {
      case "AGREEMENT_PROPOSED" -> proposal(organization, connection, command);
      case "AGREEMENT_ACCEPTED", "AGREEMENT_REJECTED" -> agreementDecision(organization, connection, command);
      case "LOCATE_REQUESTED" -> locate(connection, command);
      case "RESERVATION_REQUESTED" -> reserve(connection, command);
      case "RESERVATION_RELEASE_REQUESTED" -> {
        var r =
            found(
                reservations.findByConnectionIdAndBorrowerReservationId(
                    connection.getId(), command.referenceId()));
        sameAgreement(r.getMslaId(), connection, command);
        reservationService.release(organization.getId(), r, false);
      }
      case "COLLATERAL_PLEDGE_REQUESTED" -> pledge(connection, command);
      case "LOAN_REQUESTED" -> loan(connection, command);
      case "COLLATERAL_RELEASE_REQUESTED" -> {
        var p =
            found(
                positions.findByConnectionIdAndBorrowerPositionId(
                    connection.getId(), command.referenceId()));
        sameAgreement(p.getMslaId(), connection, command);
        require("POSTED".equals(p.getStatus()), "Collateral is not posted");
        boolean cash = "CASH".equals(p.getAssetType());
        BigDecimal requestedCash = command.data().hasNonNull("cashAmount")
            ? command.data().path("cashAmount").decimalValue()
            : cash ? p.getCashAmount() : BigDecimal.ZERO;
        long requestedQty = command.data().hasNonNull("quantity")
            ? command.data().path("quantity").asLong() : cash ? 0L : p.getQuantity();
        require(cash
            ? requestedCash.signum() > 0 && requestedCash.scale() <= 6
                && requestedCash.compareTo(p.getCashAmount()) <= 0 && requestedQty == 0
            : requestedCash.signum() == 0 && requestedQty > 0 && requestedQty <= p.getQuantity(),
            "Invalid collateral release amount");
        p.setRequestedReleaseCash(requestedCash);
        p.setRequestedReleaseQuantity(requestedQty);
        p.setStatus("RELEASE_REQUESTED");
        events.local(
            organization.getId(), "COLLATERAL_RELEASE_REQUESTED", p.getId(), Map.of("position", p));
      }
      case "MARGIN_CALL_CANCELLATION_RESPONDED" -> {
        var loan = found(loans.findByConnectionIdAndBorrowerLoanId(connection.getId(), command.referenceId()));
        sameAgreement(loan.getMslaId(), connection, command);
        require(command.data().path("accepted").isBoolean(), "Missing cancellation decision");
        marginCalls.cancellationDecision(connection, command.referenceId(), command.data().path("marginCallId").asLong(),
            command.data().path("cancellationReference").asText(), command.data().path("accepted").asBoolean(),
            command.data().path("reason").asText("Collateral already submitted"));
      }
      case "RETURN_REQUESTED" -> loanReturn(connection, command);
      case "FEE_PAYMENT_RECEIVED" -> feePayment(organization, connection, command);
      default ->
          throw new IllegalArgumentException("Unsupported borrower command " + command.type());
    }
    var receipt = new InboxEvent();
    receipt.setEventId(command.eventId());
    receipt.setOrganizationId(organization.getId());
    receipt.setPayloadHash(hash);
    receipt.setSource(counterpartyId);
    receipt.setCreatedAt(clock.instant());
    inbox.save(receipt);
    events.audit(organization.getId(), "BORROWER_COMMAND_RECEIVED", command.eventId());
  }

  private Msla remoteAgreement(BorrowerConnection c, BorrowerCommand command) {
    return found(agreements.findByConnectionIdAndBorrowerMslaId(c.getId(), command.mslaId()));
  }

  private void sameAgreement(long localId, BorrowerConnection c, BorrowerCommand command) {
    require(
        remoteAgreement(c, command).getId() == localId,
        "Command MSLA does not match the target resource");
  }

  private void proposal(Organization organization, BorrowerConnection c, BorrowerCommand command) {
    var r = json.data(command.data(), AgreementProposal.class);
    require(command.referenceId() == command.mslaId(), "Agreement reference mismatch");
    require(
        r.lenderId() == organization.getPublicReferenceId(),
        "Agreement addresses a different lender reference");
    require(
        !r.expiryDate().isBefore(r.effectiveDate()) && (r.dayBasis() == 360 || r.dayBasis() == 365),
        "Invalid agreement dates/day basis");
    require(
        agreements.findByConnectionIdAndBorrowerMslaId(c.getId(), command.referenceId()).isEmpty(),
        "Agreement request already exists");
    var a = new Msla();
    a.setOrganizationId(c.getOrganizationId());
    a.setConnectionId(c.getId());
    a.setBorrowerMslaId(command.referenceId());
    a.setNumber(r.number());
    a.setStatus("PROPOSED");
    a.setEffectiveDate(r.effectiveDate());
    a.setExpiryDate(r.expiryDate());
    a.setCreditLimit(r.creditLimit());
    a.setFeeRate(r.feeRate());
    a.setRebateRate(r.rebateRate());
    a.setCoverageRatio(r.coverageRatio());
    a.setBillingFrequency(r.billingFrequency());
    a.setRebateSettlementFrequency(r.rebateSettlementFrequency());
    a.setDayBasis(r.dayBasis());
    a.setRecallAllowed(r.recallAllowed());
    a.setMarginHours(r.marginHours());
    a.setRecallHours(r.recallHours());
    a.setTerms(json.write(r));
    a.setCreatedAt(clock.instant());
    agreements.saveAndFlush(a);
    var unique = new HashSet<String>();
    for (var rule : r.collateralRules()) {
      boolean cash = "CASH".equals(rule.assetType());
      require(
          cash
              ? (rule.securityId() == null && rule.haircut().signum() == 0)
              : rule.securityId() != null,
          "Invalid collateral eligibility rule");
      require(unique.add(rule.assetType() + ":" + rule.securityId()), "Duplicate collateral rule");
      if (!cash) found(securities.findById(rule.securityId()));
      var h = new CollateralHaircut();
      h.setOrganizationId(c.getOrganizationId());
      h.setMslaId(a.getId());
      h.setAssetId(found(assets.findByAssetName(rule.assetType())).getId());
      h.setAssetType(rule.assetType());
      h.setSecurityId(rule.securityId());
      h.setHaircut(rule.haircut());
      rules.save(h);
    }
    events.local(
        c.getOrganizationId(), "AGREEMENT_PROPOSAL_RECEIVED", a.getId(), Map.of("agreement", a));
  }

  private void agreementDecision(
      Organization organization, BorrowerConnection connection, BorrowerCommand command) {
    var data = json.map(command.data());
    Object lenderAgreement = data.get("lenderAgreementId");
    require(lenderAgreement != null, "Agreement decision is missing lenderAgreementId");
    long lenderAgreementId = new BigDecimal(lenderAgreement.toString()).longValueExact();
    String reason = data.get("reason") == null ? "" : data.get("reason").toString();
    agreementService.recordBorrowerDecision(
        organization.getId(),
        connection,
        lenderAgreementId,
        command.referenceId(),
        "AGREEMENT_ACCEPTED".equals(command.type()),
        reason);
  }

  private void locate(BorrowerConnection c, BorrowerCommand command) {
    var r = json.data(command.data(), LocateCommand.class);
    var a = remoteAgreement(c, command);
    require(r.agreementId() == command.mslaId(), "Locate MSLA mismatch");
    found(securities.findById(r.securityId()));
    require(
        locates.findByConnectionIdAndBorrowerLocateId(c.getId(), command.referenceId()).isEmpty(),
        "Locate request already exists");
    var l = new LocateRequest();
    l.setOrganizationId(c.getOrganizationId());
    l.setConnectionId(c.getId());
    l.setMslaId(a.getId());
    l.setBorrowerLocateId(command.referenceId());
    l.setSecurityId(r.securityId());
    l.setQuantity(r.quantity());
    l.setApprovedQuantity(0L);
    l.setStatus("PENDING");
    l.setExpiresAt(r.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    l.setCreatedAt(clock.instant());
    locates.saveAndFlush(l);
    events.lifecycle(
        c.getOrganizationId(),
        "REGISTER_LOCATE_EXPIRY",
        l.getId(),
        Map.of("expiresAt", l.getExpiresAt(), "connectionId", c.getId()));
    events.local(c.getOrganizationId(), "LOCATE_RECEIVED", l.getId(), Map.of("locate", l));
  }

  private void reserve(BorrowerConnection c, BorrowerCommand command) {
    var r = json.data(command.data(), ReservationCommand.class);
    var l = found(locates.findByConnectionIdAndBorrowerLocateId(c.getId(), r.locateId()));
    sameAgreement(l.getMslaId(), c, command);
    require(
        Set.of("APPROVED", "PARTIALLY_APPROVED").contains(l.getStatus())
            && r.quantity() <= l.getApprovedQuantity(),
        "Locate does not approve this quantity");
    require(reservations.findByLocateId(l.getId()).isEmpty(), "Locate already has a reservation");
    var x = new Reservation();
    x.setOrganizationId(c.getOrganizationId());
    x.setConnectionId(c.getId());
    x.setMslaId(l.getMslaId());
    x.setLocateId(l.getId());
    x.setSecurityId(l.getSecurityId());
    x.setBorrowerReservationId(command.referenceId());
    x.setQuantity(r.quantity());
    x.setRemainingQuantity(0L);
    x.setStatus("REQUESTED");
    x.setRevision(0L);
    x.setExpiresAt(l.getExpiresAt());
    x.setCreatedAt(clock.instant());
    reservations.saveAndFlush(x);
    events.local(
        c.getOrganizationId(), "RESERVATION_RECEIVED", x.getId(), Map.of("reservation", x));
  }

  private void pledge(BorrowerConnection c, BorrowerCommand command) {
    var r = json.data(command.data(), PledgeCommand.class);
    var allocation = r.allocation();
    var a = remoteAgreement(c, command);
    boolean cash = "CASH".equals(allocation.assetType());
    require(
        cash
            ? (allocation.securityId() == null
                && allocation.quantity() == 0
                && allocation.cashAmount().signum() > 0)
            : (allocation.securityId() != null
                && allocation.quantity() > 0
                && allocation.cashAmount().signum() == 0),
        "Invalid cash/security allocation");
    var rule =
        rules.findByMslaId(a.getId()).stream()
            .filter(
                x ->
                    x.getAssetType().equals(allocation.assetType())
                        && Objects.equals(x.getSecurityId(), allocation.securityId()))
            .findFirst();
    require(rule.isPresent(), "Collateral is not eligible under MSLA");
    require(
        positions.findByConnectionIdAndBorrowerPositionId(c.getId(), r.positionId()).isEmpty(),
        "Collateral position already exists");
    var loan = loans.findByConnectionIdAndBorrowerLoanId(c.getId(), command.referenceId());
    if (loan.isPresent())
      require(
          Set.of("PENDING_REVIEW", "APPROVED_PENDING_SETTLEMENT", "ACTIVE")
              .contains(loan.get().getStatus()),
          "Loan cannot accept collateral");
    var p = new CollateralPosition();
    p.setOrganizationId(c.getOrganizationId());
    p.setConnectionId(c.getId());
    p.setMslaId(a.getId());
    p.setLoanId(loan.map(Loan::getId).orElse(null));
    p.setBorrowerLoanId(command.referenceId());
    p.setBorrowerPositionId(r.positionId());
    p.setAssetId(rule.get().getAssetId());
    p.setAssetType(allocation.assetType());
    p.setSecurityId(allocation.securityId());
    p.setQuantity(allocation.quantity());
    p.setCashAmount(allocation.cashAmount());
    p.setHaircut(rule.get().getHaircut());
    p.setMarginCallId(allocation.marginCallId());
    p.setStatus("PLEDGE_PENDING");
    p.setCreatedAt(clock.instant());
    positions.saveAndFlush(p);
    collateral.movement(p, "PLEDGE_REQUESTED", command.eventId());
    events.local(
        c.getOrganizationId(), "COLLATERAL_PLEDGE_RECEIVED", p.getId(), Map.of("position", p));
  }

  private void loan(BorrowerConnection c, BorrowerCommand command) {
    var r = json.data(command.data(), LoanCommand.class);
    var reservation =
        found(
            reservations.findByConnectionIdAndBorrowerReservationId(c.getId(), r.reservationId()));
    sameAgreement(reservation.getMslaId(), c, command);
    require(
        loans.findByConnectionIdAndBorrowerLoanId(c.getId(), command.referenceId()).isEmpty(),
        "Loan request already exists");
    boolean eligible =
        "ACTIVE".equals(reservation.getStatus())
            && reservation.getExpiresAt().isAfter(clock.instant())
            && r.quantity() <= reservation.getRemainingQuantity()
            && (r.maturityAt() == null || r.maturityAt().isAfter(clock.instant()));
    var l = new Loan();
    l.setOrganizationId(c.getOrganizationId());
    l.setConnectionId(c.getId());
    l.setMslaId(reservation.getMslaId());
    l.setReservationId(reservation.getId());
    l.setSecurityId(reservation.getSecurityId());
    l.setBorrowerLoanId(command.referenceId());
    l.setMaturityAt(r.maturityAt());
    l.setQuantity(r.quantity());
    l.setOutstandingQuantity(eligible ? r.quantity() : 0L);
    l.setStatus(eligible ? "PENDING_REVIEW" : "REJECTED");
    l.setCreatedAt(clock.instant());
    loans.saveAndFlush(l);
    for (var p : positions.findByConnectionIdAndBorrowerLoanId(c.getId(), command.referenceId())) {
      require(p.getMslaId().equals(l.getMslaId()), "Staged collateral MSLA mismatch");
      p.setLoanId(l.getId());
      if (!eligible && "PLEDGE_PENDING".equals(p.getStatus()))
        collateral.reject(c.getOrganizationId(), p.getId(), "Reservation unavailable");
    }
    if (eligible) {
      reservation.setRemainingQuantity(reservation.getRemainingQuantity() - r.quantity());
      reservation.setRevision(reservation.getRevision() + 1);
      if (reservation.getRemainingQuantity() == 0) reservation.setStatus("CONSUMED");
    } else
      events.confirm(
          c,
          "LOAN_REJECTED",
          "loan",
          l.getBorrowerLoanId(),
          Map.of("reason", "Reservation unavailable, expired or insufficient"));
    events.local(c.getOrganizationId(), "LOAN_REQUEST_RECEIVED", l.getId(), Map.of("loan", l));
  }

  private void loanReturn(BorrowerConnection c, BorrowerCommand command) {
    var r = json.data(command.data(), ReturnCommand.class);
    var loan = found(loans.findByConnectionIdAndBorrowerLoanId(c.getId(), r.loanId()));
    sameAgreement(loan.getMslaId(), c, command);
    // A durable failure cancellation prevents delayed/replayed requests from settling restored assets.
    if (returnCancellations.findByConnectionIdAndBorrowerReturnId(c.getId(), command.referenceId()).isPresent()) return;
    require(
        returns.findByConnectionIdAndBorrowerReturnId(c.getId(), command.referenceId()).isEmpty(),
        "Return request already exists");
    try {
      require("ACTIVE".equals(loan.getStatus()), "Loan is not active");
      long pending =
          returns.findByLoanId(loan.getId()).stream()
              .filter(x -> "PENDING".equals(x.getStatus()))
              .mapToLong(LoanReturn::getQuantity)
              .sum();
      require(
          r.quantity() <= loan.getOutstandingQuantity() - pending,
          "Return exceeds uncommitted outstanding quantity");
      if (r.recallId() != 0) recalls.validateReturn(loan, r.recallId(), r.quantity());
      if (r.marginCallId() != 0) {
        var call = found(marginCallsRepository.findByOrganizationIdAndOwnerReferenceId(
            c.getOrganizationId(), r.marginCallId()));
        require(call.getLoanId().equals(loan.getId())
                && call.getConnectionId().equals(c.getId())
                && Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "OVERDUE").contains(call.getStatus()),
            "Margin call does not match this active borrower loan");
      }
    } catch (ResponseStatusException rejected) {
      events.confirm(
          c,
          "RETURN_REJECTED",
          "loan_return",
          command.referenceId(),
          Map.of("reason", rejected.getReason()));
      return;
    }
    var x = new LoanReturn();
    x.setOrganizationId(c.getOrganizationId());
    x.setConnectionId(c.getId());
    x.setLoanId(loan.getId());
    x.setBorrowerReturnId(command.referenceId());
    x.setQuantity(r.quantity());
    x.setRecallId(r.recallId() == 0 ? null : r.recallId());
    if (r.marginCallId() != 0) {
      var call = found(marginCallsRepository.findByOrganizationIdAndOwnerReferenceId(c.getOrganizationId(), r.marginCallId()));
      x.setMarginCallId(call.getId());
    }
    x.setStatus("PENDING");
    x.setCreatedAt(clock.instant());
    returns.saveAndFlush(x);
    events.local(
        c.getOrganizationId(),
        "LOAN_RETURN_RECEIVED",
        x.getId(),
        Map.of("return", x, "loan", loan));
  }

  /**
   * The borrower sends this only after a lender-settled return. The funds were blocked when the
   * return was requested, so accepting the command clears the lender's owner-issued bill and
   * completes the borrower cash settlement through a versioned confirmation.
   */
  private void feePayment(Organization organization, BorrowerConnection c, BorrowerCommand command) {
    var payment = json.data(command.data(), FeePaymentCommand.class);
    var loan = found(loans.findByConnectionIdAndBorrowerLoanId(c.getId(), payment.loanId()));
    sameAgreement(loan.getMslaId(), c, command);
    var fee =
        found(fees.findByOrganizationIdAndOwnerReferenceId(organization.getId(), command.referenceId()));
    require(
        fee.getConnectionId().equals(c.getId())
            && fee.getLoanId().equals(loan.getId())
            && "DUE".equals(fee.getStatus())
            && payment.amount().compareTo(fee.getAmount()) == 0,
        "Fee payment does not match an unpaid lender bill");
    fee.setStatus("PAID");
    var account = found(accounts.findByOrganizationId(organization.getId()));
    account.setFeeReceived(account.getFeeReceived().add(payment.amount()));
    var entry = new LenderAccountLedger();
    entry.setOrganizationId(organization.getId());
    entry.setLoanId(loan.getId());
    entry.setType("FEE_PAYMENT_RECEIVED");
    entry.setAmount(payment.amount());
    entry.setReference(command.eventId());
    entry.setCreatedAt(clock.instant());
    ledger.save(entry);
    events.confirm(
        c,
        "PAYMENT_CONFIRMED",
        "payment_request",
        payment.borrowerPaymentRequestId(),
        Map.of("amount", payment.amount()));
  }
}
