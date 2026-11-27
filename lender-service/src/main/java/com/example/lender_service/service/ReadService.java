package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;

import com.example.lender_service.dto.AgreementExpanded;
import com.example.lender_service.dto.LedgerSummary;
import com.example.lender_service.dto.AgreementSummary;
import com.example.lender_service.dto.FixMonitorEntry;
import com.example.lender_service.dto.InventoryExpanded;
import com.example.lender_service.dto.InventorySummary;
import com.example.lender_service.dto.MarginCallSummary;
import com.example.lender_service.dto.RecallSummary;
import com.example.lender_service.dto.ReturnSummary;
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
  private final InboxEventRepository inboxEvent;
  private final OutboxEventRepository outboxEvent;

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
      case "ledger" -> ledgerSummaries(org, paging);
      case "notifications" -> PageResponse.from(notification.findByOrganizationId(org, paging));
      case "audit" -> PageResponse.from(auditEvent.findByOrganizationId(org, paging));
      default -> throw new IllegalArgumentException("Unknown resource");
    };
  }

  private PageResponse<LedgerSummary> ledgerSummaries(
    long org,
    Pageable paging
  ) {
    var page = lenderAccountLedger.findByOrganizationId(org, paging);
    var loansById = new HashMap<Long, Loan>();
    loan
      .findAllById(
        page
          .getContent()
          .stream()
          .map(LenderAccountLedger::getLoanId)
          .filter(Objects::nonNull)
          .distinct()
          .toList()
      )
      .stream()
      .filter(l -> l.getOrganizationId().equals(org))
      .forEach(l -> loansById.put(l.getId(), l));
    var borrowersById = new HashMap<Long, BorrowerConnection>();
    borrowerConnection
      .findAllById(
        loansById
          .values()
          .stream()
          .map(Loan::getConnectionId)
          .distinct()
          .toList()
      )
      .stream()
      .filter(b -> b.getOrganizationId().equals(org))
      .forEach(b -> borrowersById.put(b.getId(), b));
    return PageResponse.from(
      page.map(entry -> {
        var linkedLoan = loansById.get(entry.getLoanId());
        var borrower =
          linkedLoan == null
            ? null
            : borrowersById.get(linkedLoan.getConnectionId());
        return new LedgerSummary(
          entry.getId(),
          entry.getOrganizationId(),
          entry.getType(),
          entry.getAmount(),
          entry.getReference(),
          entry.getCreatedAt(),
          linkedLoan == null ? null : linkedLoan.getId(),
          borrower == null ? null : borrower.getId(),
          borrower == null ? null : borrower.getName(),
          linkedLoan == null ? null : linkedLoan.getBorrowerLoanId(),
          entry.getExecutionFeeAmount()
        );
      })
    );
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

  /** Compact connected-counterparty view used by the LendDesk borrower register. */
  public Object borrower(long org, long connectionId) {
    var connection = found(borrowerConnection.findByIdAndOrganizationId(connectionId, org));
    var agreements = msla.findByOrganizationIdAndConnectionId(org, connectionId).stream()
        .sorted(Comparator.comparing(Msla::getId).reversed()).map(this::toAgreementSummary).toList();
    var loans = loan.findByOrganizationIdAndConnectionId(org, connectionId).stream()
        .sorted(Comparator.comparing(Loan::getId).reversed()).toList();
    var margins = marginCall.findByOrganizationIdAndConnectionId(org, connectionId).stream()
        .sorted(Comparator.comparing(MarginCall::getCreatedAt).reversed()).toList();
    var recalls = recall.findByOrganizationIdAndConnectionId(org, connectionId).stream()
        .sorted(Comparator.comparing(Recall::getCreatedAt).reversed()).toList();
    return Map.of(
        "borrower", connection,
        "agreements", agreements,
        "loans", loans,
        "marginCalls", margins,
        "recalls", recalls);
  }

  public PageResponse<ReturnSummary> returnSummaries(long org, int page, int size) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200)
      throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    var result = loanReturn.findByOrganizationId(org, paging);
    var content =
        result.getContent().stream()
            .map(
                returned -> {
                  var loanValue = loan.findById(returned.getLoanId()).orElse(null);
                  var security =
                      loanValue == null
                          ? null
                          : securities.findById(loanValue.getSecurityId()).orElse(null);
                  var connection =
                      loanValue == null
                          ? null
                          : borrowerConnection.findById(loanValue.getConnectionId()).orElse(null);
                  var positions = collateralPosition.findByLoanId(returned.getLoanId());
                  String collateralState =
                      positions.isEmpty()
                          ? "—"
                          : positions.stream().anyMatch(p -> "RELEASE_REQUESTED".equals(p.getStatus()))
                              ? "RELEASE_REQUESTED"
                              : positions.stream().allMatch(p -> "RELEASED".equals(p.getStatus()))
                                  ? "RELEASED"
                                  : positions.get(0).getStatus();
                  return new ReturnSummary(
                      returned.getId(),
                      returned.getLoanId(),
                      returned.getRecallId(),
                      returned.getBorrowerReturnId(),
                      returned.getQuantity(),
                      returned.getStatus(),
                      returned.getSettlementReference(),
                      returned.getCreatedAt(),
                      returned.getSettledAt(),
                      loanValue == null ? null : loanValue.getSecurityId(),
                      security == null ? null : security.getSymbol(),
                      security == null ? null : security.getName(),
                      loanValue == null ? null : loanValue.getStatus(),
                      loanValue == null ? null : loanValue.getClosedAt(),
                      loanValue == null ? null : loanValue.getConnectionId(),
                      connection == null ? null : connection.getName(),
                      collateralState,
                      returned.getMarginCallId() == null ? null : marginCall.findByIdAndOrganizationId(returned.getMarginCallId(), org).map(MarginCall::getOwnerReferenceId).orElse(null));
                })
            .toList();
    return new PageResponse<>(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  public PageResponse<MarginCallSummary> marginCallSummaries(long org, int page, int size) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200)
      throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    var result = marginCall.findByOrganizationId(org, paging);
    var content =
        result.getContent().stream()
            .map(
                call -> {
                  var loanValue = loan.findById(call.getLoanId()).orElse(null);
                  var security =
                      loanValue == null
                          ? null
                          : securities.findById(loanValue.getSecurityId()).orElse(null);
                  var connection = borrowerConnection.findById(call.getConnectionId()).orElse(null);
                  BigDecimal fulfilled = call.getFulfilledAmount() == null ? BigDecimal.ZERO : call.getFulfilledAmount();
                  return new MarginCallSummary(
                      call.getId(),
                      call.getOwnerReferenceId(),
                      call.getLoanId(),
                      call.getConnectionId(),
                      connection == null ? null : connection.getName(),
                      loanValue == null ? null : loanValue.getSecurityId(),
                      security == null ? null : security.getSymbol(),
                      security == null ? null : security.getName(),
                      call.getAmount(),
                      fulfilled,
                      call.getAmount().subtract(fulfilled).max(BigDecimal.ZERO),
                      call.getDueAt(),
                      call.getReason(),
                      call.getStatus(),
                      call.getCreatedAt(),
                      Set.of("PENDING", "OPEN", "OVERDUE").contains(call.getStatus()) && fulfilled.signum() == 0
                          && collateralPosition.findByLoanId(call.getLoanId()).stream().noneMatch(p ->
                              Objects.equals(p.getMarginCallId(), call.getOwnerReferenceId())
                                  && !p.getCreatedAt().isBefore(call.getCreatedAt())
                                  && !Set.of("REJECTED", "FAILED", "CANCELLED").contains(p.getStatus())),
                      call.getCancellationFailureReason());
                })
            .toList();
    return new PageResponse<>(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  public PageResponse<RecallSummary> recallSummaries(long org, int page, int size) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200)
      throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    var result = recall.findByOrganizationId(org, paging);
    var content =
        result.getContent().stream()
            .map(
                value -> {
                  var loanValue = loan.findById(value.getLoanId()).orElse(null);
                  var security =
                      loanValue == null
                          ? null
                          : securities.findById(loanValue.getSecurityId()).orElse(null);
                  var connection = borrowerConnection.findById(value.getConnectionId()).orElse(null);
                  long pending =
                      loanReturn
                          .findByLoanIdAndRecallIdAndStatus(
                              value.getLoanId(), value.getOwnerReferenceId(), "PENDING")
                          .stream()
                          .mapToLong(LoanReturn::getQuantity)
                          .sum();
                  return new RecallSummary(
                      value.getId(),
                      value.getOwnerReferenceId(),
                      value.getLoanId(),
                      value.getConnectionId(),
                      connection == null ? null : connection.getName(),
                      loanValue == null ? null : loanValue.getSecurityId(),
                      security == null ? null : security.getSymbol(),
                      security == null ? null : security.getName(),
                      value.getQuantity(),
                      value.getFulfilledQuantity(),
                      Math.max(0, value.getQuantity() - value.getFulfilledQuantity()),
                      pending,
                      value.getDueAt(),
                      value.getReason(),
                      value.getStatus(),
                      value.getCreatedAt());
                })
            .toList();
    return new PageResponse<>(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  public Map<String, Object> fixMonitor(long org, int size) {
    tenants.exists(org);
    if (size < 1 || size > 200) throw new IllegalArgumentException("size must be 1..200");
    var paging = PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    var messages = new ArrayList<FixMonitorEntry>();
    inboxEvent
        .findByOrganizationId(org, paging)
        .forEach(
            event ->
                messages.add(
                    new FixMonitorEntry(
                        "IN-" + event.getEventId(),
                        event.getEventId(),
                        "IN",
                        event.getCreatedAt(),
                        event.getSource(),
                        "LENDER_SERVICE",
                        "Borrower command",
                        event.getEventId(),
                        "PROCESSED",
                        "Inbound event accepted by lender service")));
    outboxEvent
        .findByOrganizationId(org, paging)
        .forEach(
            event ->
                messages.add(
                    new FixMonitorEntry(
                        "OUT-" + event.getId(),
                        event.getEventId(),
                        "OUT",
                        event.getCreatedAt(),
                        "LENDER_SERVICE",
                        event.getTopic(),
                        event.getTopic(),
                        event.getPartitionKey(),
                        Boolean.TRUE.equals(event.getPublished()) ? "PUBLISHED" : "PENDING",
                        event.getLastError() == null ? "Outbound event queued for gateway delivery" : event.getLastError())));
    messages.sort(Comparator.comparing(FixMonitorEntry::createdAt).reversed());
    List<FixMonitorEntry> limited =
        messages.size() > size ? new ArrayList<>(messages.subList(0, size)) : messages;
    long inbound = limited.stream().filter(x -> "IN".equals(x.direction())).count();
    long outbound = limited.size() - inbound;
    return Map.of("messages", limited, "inbound", inbound, "outbound", outbound);
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
    var onLoanValue =
        allLoans.stream()
            .filter(l -> Set.of("ACTIVE", "RETURN_PENDING", "DEFAULTED").contains(l.getStatus()))
            .map(l -> market.price(l.getSecurityId()).value().multiply(BigDecimal.valueOf(l.getOutstandingQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    long openMargins =
        marginCall.findByOrganizationId(org, Pageable.unpaged()).getContent().stream()
            .filter(value -> Set.of("OPEN", "PENDING", "PARTIALLY_FULFILLED", "OVERDUE").contains(value.getStatus()))
            .count();
    long openRecalls =
        recall.findByOrganizationId(org, Pageable.unpaged()).getContent().stream()
            .filter(value -> Set.of("OPEN", "PENDING", "PARTIALLY_FULFILLED", "OVERDUE").contains(value.getStatus()))
            .count();
    var dashboard = new LinkedHashMap<String, Object>();
    dashboard.put("account", account(org));
    dashboard.put("totalQuantity", stock.stream().mapToLong(InventoryPosition::getTotalQuantity).sum());
    dashboard.put("availableQuantity", stock.stream().mapToLong(InventoryPosition::getAvailableQuantity).sum());
    dashboard.put("reservedQuantity", stock.stream().mapToLong(InventoryPosition::getReservedQuantity).sum());
    dashboard.put("loanedQuantity", stock.stream().mapToLong(InventoryPosition::getLoanedQuantity).sum());
    dashboard.put("onLoanValue", onLoanValue);
    dashboard.put("activeLoans", allLoans.stream().filter(l -> l.getStatus().equals("ACTIVE")).count());
    dashboard.put("pendingLoans", allLoans.stream().filter(l -> l.getStatus().equals("PENDING_REVIEW")).count());
    dashboard.put("openMarginCalls", openMargins);
    dashboard.put("openRecalls", openRecalls);
    dashboard.put(
        "recentNotifications",
        notification
            .findByOrganizationId(org, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id")))
            .getContent());
    return dashboard;
  }

  @Transactional
  public void markRead(long org, long id) {
    tenants.lock(org);
    found(notification.findByIdAndOrganizationId(id, org)).setIsRead(true);
  }
}
