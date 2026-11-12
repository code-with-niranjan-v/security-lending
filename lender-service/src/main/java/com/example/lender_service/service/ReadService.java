package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;

import com.example.lender_service.dto.AgreementExpanded;
import com.example.lender_service.dto.AgreementSummary;
import com.example.lender_service.dto.InventoryExpanded;
import com.example.lender_service.dto.InventorySummary;
import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import com.example.lender_service.response.PageResponse;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadService {
  private final TenantService tenants;
  private final LenderAccountRepository accounts;
  private final SecurityMasterRepository securities;
  private final CollateralHaircutRepository haircuts;
  private final CollateralService preview;
  private final MarketService market;
  private final JsonService json;
  private final NotificationRepository notification;
  private final BorrowerConnectionRepository borrowerConnection;
  private final LendingFeeRepository lendingFee;
  private final CollateralPositionRepository collateralPosition;
  private final RecallRepository recall;
  private final MarginCallRepository marginCall;
  private final InventoryHistoryRepository inventoryHistory;
  private final InventoryPositionRepository inventoryPosition;
  private final BuyInRepository buyIn;
  private final LoanRepository loan;
  private final AuditEventRepository auditEvent;
  private final MslaRepository msla;
  private final LenderAccountLedgerRepository lenderAccountLedger;
  private final RebateRepository rebate;
  private final LifecycleRequestRepository lifecycleRequest;
  private final RebatePaymentRepository rebatePayment;
  private final ReservationRepository reservation;
  private final DailyAccrualRepository dailyAccrual;
  private final UserRepository user;
  private final LoanValuationRepository loanValuation;
  private final LocateRequestRepository locateRequest;
  private final LoanReturnRepository loanReturn;
  private final CollateralHistoryRepository collateralHistory;
  private final LoanDefaultRepository loanDefault;

  public Object list(long org, String resource, int page, int size) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200)
      throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    return switch (resource) {
      case "users" -> PageResponse.from(user.findByOrganizationId(org, paging));
      case "borrowers" -> PageResponse.from(borrowerConnection.findByOrganizationId(org, paging));
      case "inventory" -> PageResponse.from(inventoryPosition.findByOrganizationId(org, paging));
      case "inventory-history" ->
          PageResponse.from(inventoryHistory.findByOrganizationId(org, paging));
      case "agreements" -> PageResponse.from(msla.findByOrganizationId(org, paging));
      case "locates" -> PageResponse.from(locateRequest.findByOrganizationId(org, paging));
      case "reservations" -> PageResponse.from(reservation.findByOrganizationId(org, paging));
      case "loans" -> PageResponse.from(loan.findByOrganizationId(org, paging));
      case "collateral" -> PageResponse.from(collateralPosition.findByOrganizationId(org, paging));
      case "collateral-history" ->
          PageResponse.from(collateralHistory.findByOrganizationId(org, paging));
      case "returns" -> PageResponse.from(loanReturn.findByOrganizationId(org, paging));
      case "lifecycle-requests" ->
          PageResponse.from(lifecycleRequest.findByOrganizationId(org, paging));
      case "valuations" -> PageResponse.from(loanValuation.findByOrganizationId(org, paging));
      case "margin-calls" -> PageResponse.from(marginCall.findByOrganizationId(org, paging));
      case "recalls" -> PageResponse.from(recall.findByOrganizationId(org, paging));
      case "fees" -> PageResponse.from(lendingFee.findByOrganizationId(org, paging));
      case "rebates" -> PageResponse.from(rebate.findByOrganizationId(org, paging));
      case "accruals" -> PageResponse.from(dailyAccrual.findByOrganizationId(org, paging));
      case "defaults" -> PageResponse.from(loanDefault.findByOrganizationId(org, paging));
      case "buy-ins" -> PageResponse.from(buyIn.findByOrganizationId(org, paging));
      case "rebate-payments" -> PageResponse.from(rebatePayment.findByOrganizationId(org, paging));
      case "ledger" -> PageResponse.from(lenderAccountLedger.findByOrganizationId(org, paging));
      case "notifications" -> PageResponse.from(notification.findByOrganizationId(org, paging));
      case "audit" -> PageResponse.from(auditEvent.findByOrganizationId(org, paging));
      default -> throw new IllegalArgumentException("Unknown resource");
    };
  }

  public Object agreement(long org, long id) {
    var a = found(msla.findByIdAndOrganizationId(id, org));
    return Map.of(
        "agreement",
        a,
        "terms",
        json.tree(a.getTerms()),
        "collateralRules",
        haircuts.findByMslaId(id));
  }

  public Object agreementSummaries(long org, int page, int size) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200)
      throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    var pageResult = msla.findByOrganizationId(org, paging);
    var content =
        pageResult.getContent().stream()
            .map(this::toAgreementSummary)
            .toList();
    return new PageResponse<>(
        content,
        pageResult.getNumber(),
        pageResult.getSize(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }

  public AgreementSummary agreementSummary(long org, long id) {
    var a = found(msla.findByIdAndOrganizationId(id, org));
    return toAgreementSummary(a);
  }

  public List<InventorySummary> inventorySummaries(long org, int page, int size) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200)
      throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    return inventoryPosition.findByOrganizationId(org, paging).getContent().stream()
        .map(
            position -> {
              var security = securities.findById(position.getSecurityId()).orElse(null);
              BigDecimal marketPrice =
                  security == null || security.getPrice() == null ? BigDecimal.ZERO : security.getPrice();
              BigDecimal marketValue =
                  marketPrice.multiply(BigDecimal.valueOf(position.getTotalQuantity()));
              long available = position.getAvailableQuantity();
              long onLoan = position.getLoanedQuantity();
              long reserved = position.getReservedQuantity();
              int utilisation =
                  position.getTotalQuantity() == 0
                      ? 0
                      : (int) Math.round((double) onLoan / position.getTotalQuantity() * 100d);
              String availability =
                  available <= 0 ? "Hard to borrow" : utilisation >= 80 ? "Tight" : "Available";
              return new InventorySummary(
                  position.getId(),
                  org,
                  position.getSecurityId(),
                  security == null ? null : security.getSymbol(),
                  security == null ? null : security.getName(),
                  security == null ? null : security.getIsin(),
                  "US Equity",
                  position.getTotalQuantity(),
                  onLoan,
                  reserved,
                  available,
                  marketPrice,
                  marketValue,
                  BigDecimal.ZERO,
                  utilisation,
                  availability,
                  security == null ? null : security.getPriceAt());
            })
        .toList();
  }

  public InventoryExpanded inventoryDetail(long org, long id) {
    var position = found(inventoryPosition.findByIdAndOrganizationId(id, org));
    var security = securities.findById(position.getSecurityId()).orElse(null);
    var summary =
        new InventorySummary(
            position.getId(),
            org,
            position.getSecurityId(),
            security == null ? null : security.getSymbol(),
            security == null ? null : security.getName(),
            security == null ? null : security.getIsin(),
            "US Equity",
            position.getTotalQuantity(),
            position.getLoanedQuantity(),
            position.getReservedQuantity(),
            position.getAvailableQuantity(),
            security == null || security.getPrice() == null ? BigDecimal.ZERO : security.getPrice(),
            security == null || security.getPrice() == null
                ? BigDecimal.ZERO
                : security.getPrice().multiply(BigDecimal.valueOf(position.getTotalQuantity())),
            BigDecimal.ZERO,
            position.getTotalQuantity() == 0
                ? 0
                : (int) Math.round((double) position.getLoanedQuantity() / position.getTotalQuantity() * 100d),
            position.getAvailableQuantity() <= 0 ? "Hard to borrow" : "Available",
            security == null ? null : security.getPriceAt());
    var lots =
        List.of(
            new InventoryExpanded.InventoryLot(
                "LOT-" + position.getId(),
                "DTC 2191",
                position.getTotalQuantity(),
                position.getLoanedQuantity(),
                position.getAvailableQuantity(),
                summary.availability()));
    return new InventoryExpanded(summary, lots, 1L, position.getAvailableQuantity());
  }

  public AgreementExpanded agreementExpanded(long org, long id) {
    final String activeStatus = "ACTIVE";
    final String closedStatus = "CLOSED";
    final String exposureStatus = "APPROVED_PENDING_SETTLEMENT";
    var a = found(msla.findByIdAndOrganizationId(id, org));
    var summary = toAgreementSummary(a);
    var borrower = borrowerConnection.findById(a.getConnectionId()).orElse(null);
    var loans =
        loan.findByMslaId(id).stream()
            .sorted(Comparator.comparing(Loan::getId).reversed())
            .map(
                l -> {
                  var security = securities.findById(l.getSecurityId()).orElse(null);
                  var price = market.price(l.getSecurityId());
                  var valuation =
                      loanValuation
                          .findFirstByLoanIdOrderByValuedAtDescIdDesc(l.getId())
                          .orElse(null);
                  var collateralList =
                      collateralPosition.findByLoanId(l.getId()).stream()
                          .sorted(Comparator.comparing(CollateralPosition::getId).reversed())
                          .map(
                              p -> {
                                var assetSecurity =
                                    p.getSecurityId() == null
                                        ? null
                                        : securities.findById(p.getSecurityId()).orElse(null);
                                BigDecimal assetPrice =
                                    p.getSecurityId() == null
                                        ? BigDecimal.ZERO
                                        : market.price(p.getSecurityId()).value();
                                BigDecimal marketValue =
                                    "SECURITY".equalsIgnoreCase(p.getAssetType())
                                        ? assetPrice.multiply(BigDecimal.valueOf(p.getQuantity()))
                                        : p.getCashAmount();
                                return new AgreementExpanded.CollateralSummary(
                                    p.getId(),
                                    p.getStatus(),
                                    p.getAssetType(),
                                    p.getSecurityId(),
                                    assetSecurity == null ? p.getAssetType() : assetSecurity.getSymbol(),
                                    p.getQuantity(),
                                    p.getCashAmount(),
                                    p.getHaircut(),
                                    assetPrice,
                                    marketValue);
                              })
                          .toList();
                  BigDecimal collateralValue =
                      collateralList.stream()
                          .map(AgreementExpanded.CollateralSummary::marketValue)
                          .filter(Objects::nonNull)
                          .reduce(BigDecimal.ZERO, BigDecimal::add);
                  BigDecimal loanMarketValue =
                      valuation != null && valuation.getLoanMarketValue() != null
                          ? valuation.getLoanMarketValue()
                          : price.value().multiply(BigDecimal.valueOf(l.getOutstandingQuantity()));
                  BigDecimal coverageRatio =
                      valuation != null && valuation.getCoverageRatio() != null
                          ? valuation.getCoverageRatio()
                          : (loanMarketValue.signum() == 0
                              ? BigDecimal.ZERO
                              : collateralValue.multiply(BigDecimal.valueOf(100))
                                  .divide(loanMarketValue, 4, java.math.RoundingMode.HALF_UP));
                  return new AgreementExpanded.LoanSummary(
                      l.getId(),
                      l.getBorrowerLoanId(),
                      l.getStatus(),
                      l.getSecurityId(),
                      security == null ? null : security.getSymbol(),
                      security == null ? null : security.getName(),
                      l.getQuantity(),
                      l.getOutstandingQuantity(),
                      price.value(),
                      loanMarketValue,
                      coverageRatio,
                      collateralValue,
                      valuation == null ? null : valuation.getRequiredCollateral(),
                      (long) collateralList.size(),
                      l.getSettledAt(),
                      l.getClosedAt(),
                      collateralList);
                })
            .toList();
    long activeLoanCount = loans.stream().filter(l -> activeStatus.equals(l.status())).count();
    long closedLoanCount = loans.stream().filter(l -> closedStatus.equals(l.status())).count();
    BigDecimal totalExposure =
        loans.stream()
            .filter(
                l ->
                    Set.of(exposureStatus, activeStatus, "DEFAULTED").contains(l.status()))
            .map(
                l ->
                    (l.marketValue() == null ? BigDecimal.ZERO : l.marketValue()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new AgreementExpanded(summary, borrower, loans, activeLoanCount, closedLoanCount, totalExposure);
  }

  private AgreementSummary toAgreementSummary(Msla a) {
    var connection = borrowerConnection.findById(a.getConnectionId()).orElse(null);
    Long borrowerOrgId = connection == null ? null : connection.getBorrowerOrganizationId();
    String borrowerName = connection == null ? "Unknown borrower" : connection.getName();
    String borrowerExternalId = connection == null ? null : connection.getExternalId();
    long activeLoanCount =
        loan.findByMslaId(a.getId()).stream().filter(l -> "ACTIVE".equals(l.getStatus())).count();
    long totalLoanCount = loan.findByMslaId(a.getId()).size();
    BigDecimal totalExposure =
        loan.findByMslaId(a.getId()).stream()
            .filter(l -> Set.of("APPROVED_PENDING_SETTLEMENT", "ACTIVE", "DEFAULTED").contains(l.getStatus()))
            .map(
                l ->
                    market.price(l.getSecurityId())
                        .value()
                        .multiply(BigDecimal.valueOf(l.getOutstandingQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new AgreementSummary(
        a.getId(),
        a.getOrganizationId(),
        a.getConnectionId(),
        borrowerOrgId,
        borrowerName,
        borrowerExternalId,
        a.getNumber(),
        a.getStatus(),
        a.getEffectiveDate(),
        a.getExpiryDate(),
        a.getCreditLimit(),
        a.getFeeRate(),
        a.getRebateRate(),
        a.getCoverageRatio(),
        a.getBillingFrequency(),
        a.getRebateSettlementFrequency(),
        a.getDayBasis(),
        a.getRecallAllowed(),
        activeLoanCount,
        totalLoanCount,
        totalExposure);
  }

  public Object loanRebates(long org, long loanId, String resource, int page, int size) {
    found(loan.findByIdAndOrganizationId(loanId, org));
    if (page < 0 || size < 1 || size > 200)
      throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    return switch (resource) {
      case "rebate-accruals" ->
          PageResponse.from(dailyAccrual.findByOrganizationIdAndLoanIdAndAccrualType(org, loanId, "REBATE", paging));
      case "rebate-settlements" -> PageResponse.from(rebate.findByOrganizationIdAndLoanId(org, loanId, paging));
      case "rebate-payments" -> PageResponse.from(rebatePayment.findByOrganizationIdAndLoanId(org, loanId, paging));
      default -> throw new IllegalArgumentException("Unknown rebate resource");
    };
  }

  public Object loan(long org, long id) {
    var l = found(loan.findByIdAndOrganizationId(id, org));
    var result = new LinkedHashMap<String, Object>();
    result.put("loan", l);
    result.put("agreement", found(msla.findById(l.getMslaId())));
    result.put("borrower", found(borrowerConnection.findById(l.getConnectionId())));
    result.put("security", found(securities.findById(l.getSecurityId())));
    result.put("collateral", collateralPosition.findByLoanId(id));
    result.put("returns", loanReturn.findByLoanId(id));
    result.put(
        "valuation", loanValuation.findFirstByLoanIdOrderByValuedAtDescIdDesc(id).orElse(null));
    try {
      result.put("collateralPreview", preview.preview(org, id, true, null));
    } catch (org.springframework.web.server.ResponseStatusException e) {
      result.put("previewError", e.getReason());
    }
    return result;
  }

  public Object account(long org) {
    tenants.exists(org);
    return found(accounts.findByOrganizationId(org));
  }

  public Object projection(long org, String resource, long id) {
    LifecycleProjection value =
        switch (resource) {
          case "valuations" -> found(loanValuation.findByIdAndOrganizationId(id, org));
          case "margin-calls" -> found(marginCall.findByIdAndOrganizationId(id, org));
          case "recalls" -> found(recall.findByIdAndOrganizationId(id, org));
          case "fees" -> found(lendingFee.findByIdAndOrganizationId(id, org));
          case "rebates" -> found(rebate.findByIdAndOrganizationId(id, org));
          case "accruals" -> found(dailyAccrual.findByIdAndOrganizationId(id, org));
          case "defaults" -> found(loanDefault.findByIdAndOrganizationId(id, org));
          case "buy-ins" -> found(buyIn.findByIdAndOrganizationId(id, org));
          default -> throw new IllegalArgumentException("Unknown projection");
        };
    return Map.of("view", value, "reportedData", json.tree(value.getSourceData()));
  }

  public Object dashboard(long org) {
    tenants.exists(org);
    var stock = inventoryPosition.findByOrganizationId(org, Pageable.unpaged()).getContent();
    var allLoans = loan.findByOrganizationId(org, Pageable.unpaged()).getContent();
    return Map.of(
        "account",
        account(org),
        "totalQuantity",
        stock.stream().mapToLong(InventoryPosition::getTotalQuantity).sum(),
        "availableQuantity",
        stock.stream().mapToLong(InventoryPosition::getAvailableQuantity).sum(),
        "reservedQuantity",
        stock.stream().mapToLong(InventoryPosition::getReservedQuantity).sum(),
        "loanedQuantity",
        stock.stream().mapToLong(InventoryPosition::getLoanedQuantity).sum(),
        "activeLoans",
        allLoans.stream().filter(l -> l.getStatus().equals("ACTIVE")).count(),
        "pendingLoans",
        allLoans.stream().filter(l -> l.getStatus().equals("PENDING_REVIEW")).count(),
        "recentNotifications",
        notification
            .findByOrganizationId(org, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id")))
            .getContent());
  }

  @Transactional
  public void markRead(long org, long id) {
    tenants.lock(org);
    found(notification.findByIdAndOrganizationId(id, org)).setIsRead(true);
  }
}
