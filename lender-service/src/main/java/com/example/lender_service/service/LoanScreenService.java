package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;

import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import com.example.lender_service.response.PageResponse;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped JPA projections for the active-loan register. No demo fallback or native SQL. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoanScreenService {
  private final LoanRepository loans;
  private final MslaRepository agreements;
  private final BorrowerConnectionRepository borrowers;
  private final SecurityMasterRepository securities;
  private final CollateralPositionRepository collateral;
  private final LoanValuationRepository valuations;
  private final MarginCallRepository margins;
  private final RecallRepository recalls;
  private final LoanReturnRepository returns;
  private final LifecycleRequestRepository requests;
  private final TenantService tenants;
  private final JsonService json;
  private final Clock clock;
  private static final Set<String> OPEN =
      Set.of("PENDING", "OPEN", "PARTIALLY_FULFILLED", "OVERDUE");

  static Map<String, Object> map(Object... pairs) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }

  public Map<String, Object> overview(
      long org,
      int page,
      int size,
      Long connectionId,
      Long mslaId,
      String status,
      String coverage,
      String q) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200)
      throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    if (status != null
        && !Set.of(
                "ACTIVE",
                "PENDING_REVIEW",
                "APPROVED_PENDING_SETTLEMENT",
                "CLOSED",
                "REJECTED",
                "DEFAULTED",
                "RECOVERED")
            .contains(status)) throw new IllegalArgumentException("Invalid loan status");
    if (coverage != null && !Set.of("SUFFICIENT", "SHORTFALL", "UNKNOWN").contains(coverage))
      throw new IllegalArgumentException("Invalid coverage filter");
    var all = loans.findByOrganizationId(org, SortPage()).getContent();
    var rows = rows(org, all);
    var filtered =
        rows.stream()
            .filter(x -> connectionId == null || connectionId.equals(x.get("connectionId")))
            .filter(x -> mslaId == null || mslaId.equals(x.get("mslaId")))
            .filter(x -> status == null || status.equals(x.get("status")))
            .filter(x -> coverage == null || coverage.equals(x.get("coverageStatus")))
            .filter(
                x ->
                    q == null
                        || q.isBlank()
                        || String.valueOf(x.get("borrower"))
                            .toLowerCase(Locale.ROOT)
                            .contains(q.toLowerCase(Locale.ROOT))
                        || String.valueOf(x.get("symbol"))
                            .toLowerCase(Locale.ROOT)
                            .contains(q.toLowerCase(Locale.ROOT))
                        || String.valueOf(x.get("isin"))
                            .toLowerCase(Locale.ROOT)
                            .contains(q.toLowerCase(Locale.ROOT))
                        || String.valueOf(x.get("agreementNumber"))
                            .toLowerCase(Locale.ROOT)
                            .contains(q.toLowerCase(Locale.ROOT))
                        || String.format("LOAN-%06d", (Long) x.get("id")).equalsIgnoreCase(q)
                        || String.valueOf(x.get("id")).equals(q))
            .toList();
    long offset = (long) page * size;
    var content =
        filtered.subList(
            (int) Math.min(offset, filtered.size()),
            (int) Math.min(offset + size, filtered.size()));
    var active = rows.stream().filter(x -> "ACTIVE".equals(x.get("status"))).toList();
    // Values are grouped by currency, never summed across currencies.
    var totals = new TreeMap<String, Map<String, Object>>();
    for (var row : active) {
      String currency = (String) row.get("currency");
      var total =
          totals.computeIfAbsent(
              currency,
              k ->
                  map(
                      "currency",
                      k,
                      "marketValue",
                      BigDecimal.ZERO,
                      "eligibleCollateral",
                      BigDecimal.ZERO,
                      "missingValuations",
                      0));
      for (String key : List.of("marketValue", "eligibleCollateral")) {
        if (row.get(key) != null)
          total.put(key, ((BigDecimal) total.get(key)).add((BigDecimal) row.get(key)));
      }
      if (row.get("marketValue") == null || row.get("eligibleCollateral") == null)
        total.put("missingValuations", (int) total.get("missingValuations") + 1);
    }
    return map(
        "loans",
        new PageResponse<>(
            content, page, size, filtered.size(), (int) Math.ceil((double) filtered.size() / size)),
        "activeLoanCount",
        active.size(),
        "totals",
        totals.values(),
        "openMarginCallCount",
        rows.stream().mapToLong(x -> (long) x.get("openMarginCount")).sum(),
        "openRecallCount",
        rows.stream().mapToLong(x -> (long) x.get("openRecallCount")).sum(),
        "borrowers",
        rows.stream()
            .map(x -> map("id", x.get("connectionId"), "label", x.get("borrower")))
            .distinct()
            .toList(),
        "agreements",
        rows.stream()
            .map(x -> map("id", x.get("mslaId"), "label", x.get("agreementNumber")))
            .distinct()
            .toList(),
        "asOf",
        clock.instant());
  }

  private Pageable SortPage() {
    return Pageable.unpaged(Sort.by(Sort.Direction.DESC, "id"));
  }

  private <T> Map<Long, T> index(List<T> items, Function<T, Long> key) {
    return items.stream().collect(Collectors.toMap(key, Function.identity()));
  }

  private List<Map<String, Object>> rows(long org, List<Loan> list) {
    if (list.isEmpty()) return List.of();
    var ids = list.stream().map(Loan::getId).toList();
    var aa =
        index(
            agreements.findAllById(list.stream().map(Loan::getMslaId).distinct().toList()),
            Msla::getId);
    var bb =
        index(
            borrowers.findAllById(list.stream().map(Loan::getConnectionId).distinct().toList()),
            BorrowerConnection::getId);
    var ss =
        index(
            securities.findAllById(list.stream().map(Loan::getSecurityId).distinct().toList()),
            SecurityMaster::getId);
    var vv =
        valuations.findByOrganizationIdAndLoanIdIn(org, ids).stream()
            .collect(
                Collectors.toMap(
                    LoanValuation::getLoanId,
                    Function.identity(),
                    (a, b) ->
                        a.getValuedAt().equals(b.getValuedAt())
                            ? (a.getId() > b.getId() ? a : b)
                            : a.getValuedAt().isAfter(b.getValuedAt()) ? a : b));
    var mm = margins.findByOrganizationIdAndLoanIdIn(org, ids);
    var rr = recalls.findByOrganizationIdAndLoanIdIn(org, ids);
    var qq = requests.findByOrganizationIdAndLoanIdIn(org, ids);
    var returned = returns.findByOrganizationIdAndLoanIdIn(org, ids);
    return list.stream()
        .map(
            l -> {
              var a = aa.get(l.getMslaId());
              var b = bb.get(l.getConnectionId());
              var s = ss.get(l.getSecurityId());
              var v = vv.get(l.getId());
              BigDecimal value = v == null ? null : v.getLoanMarketValue(),
                  net = v == null ? null : v.getRecognizedCollateral(),
                  required = v == null ? null : v.getRequiredCollateral();
              BigDecimal ratio =
                  value == null || value.signum() == 0
                      ? null
                      : net.divide(value, 8, RoundingMode.HALF_UP)
                          .multiply(BigDecimal.valueOf(100));
              long openMargin =
                  mm.stream()
                      .filter(x -> x.getLoanId().equals(l.getId()) && OPEN.contains(x.getStatus()))
                      .count();
              long openRecall =
                  rr.stream()
                      .filter(x -> x.getLoanId().equals(l.getId()) && OPEN.contains(x.getStatus()))
                      .count();
              long pendingRecall =
                  qq.stream()
                      .filter(
                          x ->
                              x.getLoanId().equals(l.getId())
                                  && "RECALL_REQUESTED".equals(x.getType())
                                  && Set.of("PENDING", "ACCEPTED").contains(x.getStatus()))
                      .mapToLong(x -> json.tree(x.getPayload()).path("quantity").asLong())
                      .sum();
              long committed =
                  rr.stream()
                          .filter(
                              x -> x.getLoanId().equals(l.getId()) && OPEN.contains(x.getStatus()))
                          .mapToLong(x -> x.getQuantity() - x.getFulfilledQuantity())
                          .sum()
                      + pendingRecall;
              boolean pendingMargin =
                  qq.stream()
                      .anyMatch(
                          x ->
                              x.getLoanId().equals(l.getId())
                                  && "MARGIN_CALL_REQUESTED".equals(x.getType())
                                  && Set.of("PENDING", "ACCEPTED").contains(x.getStatus()));
              return map(
                  "id",
                  l.getId(),
                  "connectionId",
                  l.getConnectionId(),
                  "mslaId",
                  l.getMslaId(),
                  "agreementNumber",
                  a.getNumber(),
                  "reservationId",
                  l.getReservationId(),
                  "borrower",
                  b.getName(),
                  "securityId",
                  l.getSecurityId(),
                  "symbol",
                  s.getSymbol(),
                  "isin",
                  s.getIsin(),
                  "currency",
                  s.getCurrency(),
                  "quantity",
                  l.getQuantity(),
                  "returnedQuantity",
                  returned.stream()
                      .filter(
                          x -> x.getLoanId().equals(l.getId()) && "SETTLED".equals(x.getStatus()))
                      .mapToLong(LoanReturn::getQuantity)
                      .sum(),
                  "outstandingQuantity",
                  l.getOutstandingQuantity(),
                  "price",
                  s.getPrice(),
                  "priceAt",
                  s.getPriceAt(),
                  "marketValue",
                  value,
                  "eligibleCollateral",
                  net,
                  "requiredCollateral",
                  required,
                  "coverage",
                  ratio,
                  "coverageStatus",
                  v == null ? "UNKNOWN" : net.compareTo(required) >= 0 ? "SUFFICIENT" : "SHORTFALL",
                  "shortfall",
                  v == null ? null : v.getShortfall(),
                  "feeBps",
                  a.getFeeRate().multiply(BigDecimal.valueOf(10000)),
                  "rebateBps",
                  a.getRebateRate().multiply(BigDecimal.valueOf(10000)),
                  "dayBasis",
                  a.getDayBasis(),
                  "billing",
                  a.getBillingFrequency(),
                  "requiredRatio",
                  a.getCoverageRatio().multiply(BigDecimal.valueOf(100)),
                  "recallHours",
                  a.getRecallHours(),
                  "marginHours",
                  a.getMarginHours(),
                  "maturity",
                  null,
                  "settledAt",
                  l.getSettledAt(),
                  "createdAt",
                  l.getCreatedAt(),
                  "settlementReference",
                  l.getSettlementReference(),
                  "status",
                  l.getStatus(),
                  "openMarginCount",
                  openMargin,
                  "openRecallCount",
                  openRecall,
                  "recallAvailableQuantity",
                  Math.max(0, l.getOutstandingQuantity() - committed),
                  "canRecall",
                  "ACTIVE".equals(l.getStatus())
                      && a.getRecallAllowed()
                      && l.getOutstandingQuantity() > committed,
                  "canIssueMargin",
                  "ACTIVE".equals(l.getStatus())
                      && v != null
                      && v.getShortfall().signum() > 0
                      && openMargin == 0
                      && !pendingMargin,
                  "pendingMarginRequest",
                  pendingMargin,
                  "valuationAt",
                  v == null ? null : v.getValuedAt());
            })
        .toList();
  }

  public Map<String, Object> detail(long org, long id) {
    var loan = found(loans.findByIdAndOrganizationId(id, org));
    var row = rows(org, List.of(loan)).get(0);
    var positions =
        collateral.findByOrganizationIdAndLoanIdIn(org, List.of(id)).stream()
            .map(
                p ->
                    map(
                        "id",
                        p.getId(),
                        "assetType",
                        p.getAssetType(),
                        "cashAmount",
                        p.getCashAmount(),
                        "quantity",
                        p.getQuantity(),
                        "haircut",
                        p.getHaircut(),
                        "status",
                        p.getStatus(),
                        "createdAt",
                        p.getCreatedAt()))
            .toList();
    var rr = returns.findByOrganizationIdAndLoanIdIn(org, List.of(id));
    var recallsList = recalls.findByOrganizationIdAndLoanIdIn(org, List.of(id));
    var marginList = margins.findByOrganizationIdAndLoanIdIn(org, List.of(id));
    var req = requests.findByOrganizationIdAndLoanIdIn(org, List.of(id));
    var activity = new ArrayList<Map<String, Object>>();
    activity.add(map("at", loan.getCreatedAt(), "event", "Loan requested", "reference", id));
    if (loan.getSettledAt() != null)
      activity.add(
          map(
              "at",
              loan.getSettledAt(),
              "event",
              "Loan settled",
              "reference",
              loan.getSettlementReference()));
    if (loan.getClosedAt() != null)
      activity.add(map("at", loan.getClosedAt(), "event", "Loan closed", "reference", id));
    for (var x : rr) {
      activity.add(
          map("at", x.getCreatedAt(), "event", "Return requested", "reference", x.getId()));
      if (x.getSettledAt() != null)
        activity.add(
            map(
                "at",
                x.getSettledAt(),
                "event",
                "Return settled (" + x.getQuantity() + ")",
                "reference",
                x.getSettlementReference()));
    }
    for (var x : req)
      activity.add(
          map(
              "at",
              x.getCreatedAt(),
              "event",
              x.getType() + " · " + x.getStatus(),
              "reference",
              x.getId()));
    activity.sort(Comparator.comparing(x -> (Instant) x.get("at"), Comparator.reverseOrder()));
    return map(
        "loan",
        row,
        "collateral",
        positions,
        "returns",
        rr,
        "recalls",
        recallsList,
        "marginCalls",
        marginList,
        "requests",
        req,
        "activity",
        activity,
        "asOf",
        clock.instant(),
        "unavailableFields",
        List.of("loanMaturity", "custodyLots", "borrowerTopUpInventory", "perLoanNegotiatedTerms"));
  }
}
