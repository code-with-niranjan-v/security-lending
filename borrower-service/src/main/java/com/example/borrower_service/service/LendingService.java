package com.example.borrower_service.service;

import static com.example.borrower_service.repository.BorrowerRepository.*;

import com.example.borrower_service.dto.*;
import com.example.borrower_service.dto.LocateRequest;
import com.example.borrower_service.model.*;
import com.example.borrower_service.repository.BorrowerRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class LendingService {
    private final BorrowerRepository db;
    private final EventWriter events;
    private final MarketService market;
    private final CollateralService collateral;
    private final PaymentService payments;
    private final Clock clock;

    public LendingService(
            BorrowerRepository db,
            EventWriter events,
            MarketService market,
            CollateralService collateral,
            PaymentService payments,
            Clock clock) {
        this.db = db;
        this.events = events;
        this.market = market;
        this.collateral = collateral;
        this.payments = payments;
        this.clock = clock;
    }

    /** The borrower may respond to lender-authored terms, but may not create MSLA terms. */
    public Map<String, Object> decideAgreement(
            long org, long agreementId, boolean accept, AgreementDecisionRequest request) {
        db.lock(org);
        var agreement = db.owned(Msla.class, org, agreementId);
        require("OFFERED".equals(agreement.get("status")), "Agreement is not awaiting borrower acceptance");
        if (accept) {
            var today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata")));
            require(!today.isAfter(date(agreement, "expiry_date")), "Agreement has expired");
        }
        db.edit(
                Msla.class,
                agreementId,
                entity -> entity.setStatus(accept ? "ACCEPTANCE_PENDING" : "REJECTION_PENDING"));
        events.write(
                org,
                accept ? "AGREEMENT_ACCEPTED" : "AGREEMENT_REJECTED",
                agreementId,
                Map.of(
                        "lenderAgreementId", n(agreement, "lender_msla_id"),
                        "reason", request == null || request.reason() == null ? "" : request.reason()));
        return db.owned(Msla.class, org, agreementId);
    }

    /**
     * Legacy fixture support for pre-existing unit tests. There is deliberately no HTTP route for
     * this method: production borrowers can only accept or reject lender offers.
     */
    @Deprecated(forRemoval = false)
    public Map<String, Object> propose(long org, AgreementRequest r) {
        db.lock(org);
        db.row(LenderReference.class, r.lenderId());
        long id = db.create(Msla::new, entity -> {
            entity.setOrganizationId(org);
            entity.setLenderId(r.lenderId());
            entity.setNumber(r.number());
            entity.setStatus("PROPOSED");
            entity.setEffectiveDate(r.effectiveDate());
            entity.setExpiryDate(r.expiryDate());
            entity.setCreditLimit(r.creditLimit());
            entity.setFeeRate(r.feeRate());
            entity.setRebateRate(r.rebateRate());
            entity.setCoverageRatio(r.coverageRatio());
            entity.setBillingFrequency(r.billingFrequency());
            entity.setRebateSettlementFrequency(r.rebateSettlementFrequency());
            entity.setDayBasis(r.dayBasis());
            entity.setRecallAllowed(r.recallAllowed());
            entity.setMarginHours(r.marginHours());
            entity.setRecallHours(r.recallHours());
        });
        for (var rule : r.collateralRules()) {
            long asset = n(db.matchingRow(Asset.class, eq("assetName", rule.assetType())), "id");
            db.create(CollateralHaircut::new, entity -> {
                entity.setMslaId(id);
                entity.setAssetType(rule.assetType());
                entity.setSecurityId(rule.securityId());
                entity.setHaircut(rule.haircut());
                entity.setAssetId(asset);
            });
        }
        return db.owned(Msla.class, org, id);
    }

    private Map<String, Object> active(long org, long id) {
        var a = db.owned(Msla.class, org, id);
        var today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata")));
        require("ACTIVE".equals(a.get("status")), "MSLA is not active");
        require(
                !today.isBefore(date(a, "effective_date"))
                        && !today.isAfter(date(a, "expiry_date")),
                "MSLA is outside its effective dates");
        return a;
    }

    public Map<String, Object> locate(long org, LocateRequest r) {
        db.lock(org);
        active(org, r.agreementId());
        db.row(SecurityMaster.class, r.securityId());
        require(r.expiresAt().isAfter(Instant.now()), "Locate expiry must be in the future");
        long id =
                db.create(
                        com.example.borrower_service.model.LocateRequest::new,
                        entity -> {
                            entity.setOrganizationId(org);
                            entity.setMslaId(r.agreementId());
                            entity.setSecurityId(r.securityId());
                            entity.setQuantity(r.quantity());
                            entity.setStatus("PENDING");
                            entity.setExpiresAt(r.expiresAt());
                        });
        events.write(org, "LOCATE_REQUESTED", id, r);
        return db.owned(com.example.borrower_service.model.LocateRequest.class, org, id);
    }

    public Map<String, Object> releaseReservation(long org, long id) {
        db.lock(org);
        var r = db.owned(Reservation.class, org, id);
        require("ACTIVE".equals(r.get("status")), "Reservation is not active");
        db.edit(
                Reservation.class,
                id,
                entity -> {
                    entity.setStatus("RELEASE_REQUESTED");
                });
        events.write(
                org,
                "RESERVATION_RELEASE_REQUESTED",
                id,
                Map.of("quantity", n(r, "remaining_quantity")));
        return db.owned(Reservation.class, org, id);
    }

    public Map<String, Object> reserve(long org, long locateId, ReservationRequest request) {
        db.lock(org);
        var locate =
                db.owned(com.example.borrower_service.model.LocateRequest.class, org, locateId);
        require(
                Set.of("APPROVED", "PARTIALLY_APPROVED").contains(locate.get("status")),
                "Locate must be approved before reservation");
        require(
                instant(locate, "expires_at").isAfter(Instant.now()),
                "Locate approval has expired");
        require(
                request.quantity() <= n(locate, "approved_quantity"),
                "Reservation exceeds approved locate quantity");
        require(
                db.rows(Reservation.class, eq("locateId", locateId)).isEmpty(),
                "This locate already has a reservation request");
        long id =
                db.create(
                        Reservation::new,
                        entity -> {
                            entity.setOrganizationId(org);
                            entity.setLocateId(locateId);
                            entity.setMslaId(n(locate, "msla_id"));
                            entity.setSecurityId(n(locate, "security_id"));
                            entity.setQuantity(request.quantity());
                            entity.setRemainingQuantity(0L);
                            entity.setStatus("REQUESTED");
                            entity.setExpiresAt(instant(locate, "expires_at"));
                        });
        events.write(
                org,
                "RESERVATION_REQUESTED",
                id,
                Map.of("locateId", locateId, "quantity", request.quantity()));
        return db.owned(Reservation.class, org, id);
    }

    public BigDecimal exposure(long org, long agreement) {
        BigDecimal result = BigDecimal.ZERO;
        for (var l :
                db.rows(
                        Loan.class,
                        eq("organizationId", org),
                        eq("mslaId", agreement),
                        oneOf(
                                "status",
                                "PENDING_SETTLEMENT",
                                "ACTIVE",
                                "RETURN_PENDING",
                                "DEFAULTED")))
            result =
                    result.add(
                            market.price(n(l, "security_id"))
                                    .value()
                                    .multiply(BigDecimal.valueOf(n(l, "outstanding_quantity"))));
        return amount(result);
    }

    public Map<String, Object> createLoan(long org, LoanRequest r) {
        db.lock(org);
        require(r.maturityAt() == null || r.maturityAt().isAfter(Instant.now()),
                "Loan maturity must be in the future");
        var reservation = db.owned(Reservation.class, org, r.reservationId());
        require(
                "ACTIVE".equals(reservation.get("status"))
                        && instant(reservation, "expires_at").isAfter(Instant.now()),
                "Reservation is not active or has expired");
        require(
                n(reservation, "remaining_quantity") >= r.quantity(),
                "Loan quantity exceeds remaining reservation");
        var a = active(org, n(reservation, "msla_id"));
        BigDecimal before = exposure(org, n(a, "id")),
                value =
                        market.price(n(reservation, "security_id"))
                                .value()
                                .multiply(BigDecimal.valueOf(r.quantity()));
        require(
                before.add(value).compareTo(money(a, "credit_limit")) <= 0,
                "MSLA credit limit exceeded");
        long id =
                db.create(
                        Loan::new,
                        entity -> {
                            entity.setOrganizationId(org);
                            entity.setMslaId(n(a, "id"));
                            entity.setReservationId(r.reservationId());
                            entity.setMaturityAt(r.maturityAt());
                            entity.setSecurityId(n(reservation, "security_id"));
                            entity.setQuantity(r.quantity());
                            entity.setOutstandingQuantity(r.quantity());
                            entity.setStatus("PENDING_SETTLEMENT");
                            entity.setFeeRate(money(a, "fee_rate"));
                            entity.setRebateRate(money(a, "rebate_rate"));
                            entity.setCoverageRatio(money(a, "coverage_ratio"));
                            entity.setDayBasis(Math.toIntExact(n(a, "day_basis")));
                            entity.setBillingFrequency((String) a.get("billing_frequency"));
                        });
        db.edit(
                Reservation.class,
                r.reservationId(),
                entity -> {
                    entity.setRemainingQuantity(entity.getRemainingQuantity() - (r.quantity()));
                });
        if (n(reservation, "remaining_quantity") == r.quantity())
            db.edit(
                    Reservation.class,
                    r.reservationId(),
                    entity -> {
                        entity.setStatus("CONSUMED");
                    });
        for (var allocation : r.collateral()) collateral.post(org, id, allocation);
        collateral.validateInitialAllocation(org, id);
        credit(org, a, "LOAN_REQUESTED", before, before.add(value));
        events.write(org, "LOAN_REQUESTED", id, r);
        return db.owned(Loan.class, org, id);
    }

    /**
     * Convert an outstanding monetary margin shortfall into a whole-share return request.
     * The call remains externally authoritative and is only fulfilled after lender confirmation.
     */
    public Map<String, Object> reduceExposure(long org, long marginCallId) {
        db.lock(org);
        var call = db.owned(MarginCall.class, org, marginCallId);
        require(Set.of("OPEN", "PARTIALLY_FULFILLED", "OVERDUE").contains(call.get("status")),
                "Margin call is not open for a response");
        BigDecimal remainingShortfall = money(call, "amount").subtract(money(call, "fulfilled_amount"));
        require(remainingShortfall.signum() > 0, "Margin call has no remaining shortfall");
        long loanId = n(call, "loan_id");
        var loan = db.owned(Loan.class, org, loanId);
        require(Set.of("ACTIVE", "RETURN_PENDING").contains(loan.get("status")),
                "Loan is not eligible for exposure reduction");
        BigDecimal price = market.price(n(loan, "security_id")).value();
        BigDecimal coverageRatio = money(loan, "coverage_ratio");
        require(price != null && price.signum() > 0, "A valid market price is required to calculate the return quantity");
        require(coverageRatio.signum() > 0, "A valid loan coverage ratio is required to calculate the return quantity");
        // Returning one share reduces the required collateral by its market value multiplied by
        // the contractual coverage ratio, not merely by the share's market value.
        BigDecimal coverageReleasedPerShare = price.multiply(coverageRatio);
        long quantity = remainingShortfall.divide(coverageReleasedPerShare, 0, RoundingMode.CEILING).longValueExact();
        require(quantity > 0, "Calculated return quantity must be positive");
        require(db.rows(LoanReturn.class, eq("marginCallId", marginCallId),
                        oneOf("status", "PENDING", "FAILED")).stream()
                        .noneMatch(row -> "PENDING".equals(row.get("status"))
                                || ("FAILED".equals(row.get("status"))
                                    && !Boolean.TRUE.equals(row.get("assets_released")))),
                "A return request is already pending for this margin call");
        var holding = db.matchingRow(Holding.class,
                eq("organizationId", org), eq("securityId", n(loan, "security_id")));
        long availableBorrowed = n(holding, "borrowed_quantity") - n(holding, "blocked_borrowed");
        require(quantity <= availableBorrowed,
                "Insufficient available borrowed securities to cover the margin shortfall");
        require(quantity <= n(loan, "outstanding_quantity"),
                "Required return quantity exceeds the outstanding loan quantity");
        return requestReturn(org, loanId, new ReturnRequest(quantity, null, marginCallId));
    }

    public Map<String, Object> requestReturn(long org, long loanId, ReturnRequest r) {
        db.lock(org);
        var loan = db.owned(Loan.class, org, loanId);
        require(
                Set.of("ACTIVE", "RETURN_PENDING").contains(loan.get("status")),
                "Loan is not active");
        if (r.marginCallId() != null) {
            var marginCall = db.owned(MarginCall.class, org, r.marginCallId());
            require(n(marginCall, "loan_id") == loanId
                            && Set.of("OPEN", "PARTIALLY_FULFILLED", "OVERDUE").contains(marginCall.get("status")),
                    "Margin call does not belong to this loan or is already resolved");
        }
        if (r.recallId() != null) {
            var recall = db.owned(Recall.class, org, r.recallId());
            require(
                    n(recall, "loan_id") == loanId
                            && Set.of("OPEN", "PARTIALLY_FULFILLED", "OVERDUE")
                                    .contains(recall.get("status")),
                    "Recall does not belong to this loan or is already resolved");
            long pendingForRecall =
                    db.sum(
                            LoanReturn.class,
                            "quantity",
                            Long.class,
                            eq("recallId", r.recallId()),
                            eq("status", "PENDING"));
            pendingForRecall += db.sum(LoanReturn.class, "quantity", Long.class,
                    eq("recallId", r.recallId()), eq("status", "FAILED"), eq("assetsReleased", false));
            require(
                    r.quantity()
                            <= n(recall, "quantity")
                                    - n(recall, "fulfilled_quantity")
                                    - pendingForRecall,
                    "Return exceeds the uncommitted recall quantity");
        }
        long pending =
                db.sum(
                        LoanReturn.class,
                        "quantity",
                        Long.class,
                        eq("loanId", loanId),
                        eq("status", "PENDING"));
        pending += db.sum(LoanReturn.class, "quantity", Long.class,
                eq("loanId", loanId), eq("status", "FAILED"), eq("assetsReleased", false));
        long outstanding = n(loan, "outstanding_quantity");
        require(
                r.quantity() <= outstanding - pending,
                "Return exceeds uncommitted outstanding loan quantity");
        long security = n(loan, "security_id");
        db.holding(org, security);
        var h =
                db.matchingRow(
                        Holding.class, eq("organizationId", org), eq("securityId", security));
        long borrowed =
                Math.min(r.quantity(), n(h, "borrowed_quantity") - n(h, "blocked_borrowed"));
        long owned = r.quantity() - borrowed;
        require(
                n(h, "owned_quantity") - n(h, "pledged_quantity") - n(h, "blocked_owned") >= owned,
                "Buy back sufficient unpledged securities before returning the loan");
        db.editMatching(
                Holding.class,
                entity -> {
                    entity.setOwnedQuantity(entity.getOwnedQuantity() - (owned));
                    entity.setBorrowedQuantity(entity.getBorrowedQuantity() - (borrowed));
                },
                eq("organizationId", org),
                eq("securityId", security));
        long id =
                db.create(
                        LoanReturn::new,
                        entity -> {
                            entity.setOrganizationId(org);
                            entity.setLoanId(loanId);
                            entity.setQuantity(r.quantity());
                            entity.setOwnedQuantity(owned);
                            entity.setBorrowedQuantity(borrowed);
                            entity.setStatus("PENDING");
                            entity.setOwnedCost(money(h, "average_cost"));
                            entity.setRecallId(r.recallId());
                            entity.setMarginCallId(r.marginCallId());
                        });
        // Fees are only reserved here. They remain blocked until lender settlement, or are
        // released if the return is rejected.
        payments.blockDueFeesForReturn(org, loanId, id);
        db.edit(
                Loan.class,
                loanId,
                entity -> {
                    entity.setStatus("RETURN_PENDING");
                });
        events.write(
                org,
                "RETURN_REQUESTED",
                id,
                Map.of(
                        "loanId",
                        loanId,
                        "quantity",
                        r.quantity(),
                        "recallId",
                        r.recallId() == null ? 0 : r.recallId(),
                        "marginCallId",
                        r.marginCallId() == null ? 0 : r.marginCallId()));
        return db.owned(LoanReturn.class, org, id);
    }

    public Map<String, Object> respondToRecall(long org, long recallId, ReturnRequest r) {
        db.lock(org);
        var recall = db.owned(Recall.class, org, recallId);
        require(
                r.recallId() == null || r.recallId() == recallId,
                "Recall reference does not match the request path");
        return requestReturn(org, n(recall, "loan_id"), new ReturnRequest(r.quantity(), recallId));
    }

    private void credit(
            long org, Map<String, Object> a, String type, BigDecimal before, BigDecimal after) {
        BigDecimal limit = money(a, "credit_limit");
        db.create(
                CreditHistory::new,
                entity -> {
                    entity.setOrganizationId(org);
                    entity.setMslaId(n(a, "id"));
                    entity.setTransactionType(type);
                    entity.setOldLimit(limit);
                    entity.setNewLimit(limit);
                    entity.setCreditChange(after.subtract(before));
                    entity.setUtilizedBefore(before);
                    entity.setUtilizedAfter(after);
                    entity.setAvailableBefore(limit.subtract(before));
                    entity.setAvailableAfter(limit.subtract(after));
                });
    }
}
