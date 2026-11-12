package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;
import static com.example.lender_service.service.LoanScreenService.map;

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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CollateralScreenService {
  private final CollateralPositionRepository positions;
  private final CollateralHistoryRepository movements;
  private final LoanRepository loans;
  private final MslaRepository agreements;
  private final BorrowerConnectionRepository borrowers;
  private final SecurityMasterRepository securities;
  private final LoanValuationRepository valuations;
  private final TenantService tenants;
  private final JsonService json;
  private final Clock clock;
  private static final Set<String> HELD = Set.of("POSTED", "RELEASE_REQUESTED");

  private <T> Map<Long, T> index(List<T> values, Function<T, Long> key) {
    return values.stream().collect(Collectors.toMap(key, Function.identity()));
  }

  private BigDecimal price(SecurityMaster s) {
    return s != null
            && s.getPrice() != null
            && s.getPrice().signum() > 0
            && s.getPriceAt() != null
            && !s.getPriceAt().isAfter(clock.instant())
        ? s.getPrice()
        : null;
  }

  private List<Map<String, Object>> rows(long org) {
    var all =
        positions
            .findByOrganizationId(org, Pageable.unpaged(Sort.by(Sort.Direction.DESC, "id")))
            .getContent();
    var ll =
        index(
            loans.findAllById(
                all.stream()
                    .map(CollateralPosition::getLoanId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList()),
            Loan::getId);
    var aa =
        index(
            agreements.findAllById(
                all.stream().map(CollateralPosition::getMslaId).distinct().toList()),
            Msla::getId);
    var bb =
        index(
            borrowers.findAllById(
                all.stream().map(CollateralPosition::getConnectionId).distinct().toList()),
            BorrowerConnection::getId);
    var securityIds = new HashSet<Long>();
    all.stream()
        .map(CollateralPosition::getSecurityId)
        .filter(Objects::nonNull)
        .forEach(securityIds::add);
    ll.values().stream().map(Loan::getSecurityId).forEach(securityIds::add);
    var ss = index(securities.findAllById(securityIds), SecurityMaster::getId);
    var result = new ArrayList<Map<String, Object>>();
    for (var p : all) {
      var l = ll.get(p.getLoanId());
      var a = aa.get(p.getMslaId());
      var b = bb.get(p.getConnectionId());
      var s = ss.get(p.getSecurityId());
      var ls = l == null ? null : ss.get(l.getSecurityId());
      boolean cash = "CASH".equals(p.getAssetType()), held = HELD.contains(p.getStatus());
      BigDecimal quote = cash ? BigDecimal.ONE : price(s);
      BigDecimal value =
          quote == null
              ? null
              : cash ? p.getCashAmount() : quote.multiply(BigDecimal.valueOf(p.getQuantity()));
      boolean sameCurrency =
          cash
              || ls == null
              || Objects.equals(s == null ? null : s.getCurrency(), ls.getCurrency());
      BigDecimal recognized =
          value == null || !sameCurrency
              ? null
              : amount(value.multiply(BigDecimal.ONE.subtract(p.getHaircut())));
      BigDecimal loanPrice = price(ls),
          required =
              l == null || loanPrice == null
                  ? null
                  : amount(
                      loanPrice
                          .multiply(BigDecimal.valueOf(l.getOutstandingQuantity()))
                          .multiply(a.getCoverageRatio()));
      result.add(
          map(
              "id",
              p.getId(),
              "loanId",
              p.getLoanId(),
              "connectionId",
              p.getConnectionId(),
              "borrower",
              b.getName(),
              "mslaId",
              p.getMslaId(),
              "agreementNumber",
              a.getNumber(),
              "assetType",
              p.getAssetType(),
              "securityId",
              p.getSecurityId(),
              "symbol",
              cash ? "Cash" : s == null ? null : s.getSymbol(),
              "currency",
              cash ? "INR" : s == null ? "INR" : s.getCurrency(),
              "quantity",
              p.getQuantity(),
              "cashAmount",
              p.getCashAmount(),
              "price",
              quote,
              "priceAt",
              cash ? null : s == null ? null : s.getPriceAt(),
              "marketValue",
              value == null ? null : amount(value),
              "haircut",
              p.getHaircut().multiply(BigDecimal.valueOf(100)),
              "eligibleValue",
              recognized,
              "includedInCoverage",
              held,
              "valuationIssue",
              quote == null
                  ? "Missing cached price"
                  : !sameCurrency ? "Currency conversion unavailable" : null,
              "loanRequiredValue",
              required,
              "status",
              p.getStatus(),
              "createdAt",
              p.getCreatedAt(),
              "settlementReference",
              p.getSettlementReference(),
              "canRunValuation",
              l != null && "ACTIVE".equals(l.getStatus()) && l.getOutstandingQuantity() > 0));
    }
    for (var row : result) {
      if (row.get("loanId") == null) {
        row.put("loanCoverage", null);
        continue;
      }
      var linked =
          result.stream()
              .filter(
                  x ->
                      Objects.equals(row.get("loanId"), x.get("loanId"))
                          && Boolean.TRUE.equals(x.get("includedInCoverage")))
              .toList();
      boolean complete = linked.stream().allMatch(x -> x.get("eligibleValue") != null);
      BigDecimal net =
          linked.stream()
              .map(x -> (BigDecimal) x.get("eligibleValue"))
              .filter(Objects::nonNull)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal required = (BigDecimal) row.get("loanRequiredValue");
      row.put("loanEligibleValue", complete ? net : null);
      row.put(
          "loanCoverage",
          !complete || required == null || required.signum() == 0
              ? null
              : net.divide(required, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
    }
    return result;
  }

  public Map<String, Object> overview(
      long org,
      int page,
      int size,
      Long connectionId,
      Long loanId,
      String assetType,
      String status,
      String q) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid paging");
    if (assetType != null && !Set.of("CASH", "SECURITY").contains(assetType))
      throw new IllegalArgumentException("Invalid asset type");
    if (status != null
        && !Set.of("PLEDGE_PENDING", "POSTED", "RELEASE_REQUESTED", "RELEASED", "REJECTED")
            .contains(status)) throw new IllegalArgumentException("Invalid collateral status");
    var all = rows(org);
    String search = q == null ? "" : q.toLowerCase(Locale.ROOT);
    var filtered =
        all.stream()
            .filter(x -> connectionId == null || connectionId.equals(x.get("connectionId")))
            .filter(x -> loanId == null || loanId.equals(x.get("loanId")))
            .filter(x -> assetType == null || assetType.equals(x.get("assetType")))
            .filter(x -> status == null || status.equals(x.get("status")))
            .filter(
                x ->
                    search.isBlank()
                        || (x.get("borrower")
                                + " "
                                + x.get("symbol")
                                + " "
                                + x.get("agreementNumber")
                                + " COL-"
                                + String.format("%06d", x.get("id"))
                                + " LOAN-"
                                + (x.get("loanId") == null
                                    ? ""
                                    : String.format("%06d", x.get("loanId"))))
                            .toLowerCase(Locale.ROOT)
                            .contains(search))
            .toList();
    var totals = new TreeMap<String, Map<String, Object>>();
    for (var row : all) {
      if (!Boolean.TRUE.equals(row.get("includedInCoverage"))) continue;
      String currency = (String) row.get("currency");
      var t =
          totals.computeIfAbsent(
              currency,
              k ->
                  map(
                      "currency",
                      k,
                      "marketValue",
                      BigDecimal.ZERO,
                      "eligibleValue",
                      BigDecimal.ZERO,
                      "missingPrices",
                      0,
                      "positions",
                      0));
      t.put("positions", (int) t.get("positions") + 1);
      if (row.get("eligibleValue") == null) {
        t.put("missingPrices", (int) t.get("missingPrices") + 1);
        continue;
      }
      t.put(
          "marketValue",
          ((BigDecimal) t.get("marketValue")).add((BigDecimal) row.get("marketValue")));
      t.put(
          "eligibleValue",
          ((BigDecimal) t.get("eligibleValue")).add((BigDecimal) row.get("eligibleValue")));
    }
    for (var t : totals.values())
      if (t.get("positions").equals(t.get("missingPrices"))) {
        t.put("marketValue", null);
        t.put("eligibleValue", null);
      }
    long start = (long) page * size;
    return map(
        "positions",
        new PageResponse<>(
            filtered.subList(
                (int) Math.min(start, filtered.size()),
                (int) Math.min(start + size, filtered.size())),
            page,
            size,
            filtered.size(),
            (int) Math.ceil((double) filtered.size() / size)),
        "totals",
        totals.values(),
        "borrowers",
        all.stream()
            .map(x -> map("id", x.get("connectionId"), "label", x.get("borrower")))
            .distinct()
            .toList(),
        "loans",
        all.stream()
            .filter(x -> x.get("loanId") != null)
            .map(
                x ->
                    map(
                        "id",
                        x.get("loanId"),
                        "label",
                        "LOAN-" + String.format("%06d", x.get("loanId"))))
            .distinct()
            .toList(),
        "asOf",
        clock.instant());
  }

  public Map<String, Object> detail(long org, long id) {
    var p = found(positions.findByIdAndOrganizationId(id, org));
    var row =
        rows(org).stream().filter(x -> Objects.equals(x.get("id"), id)).findFirst().orElseThrow();
    var history = new ArrayList<Map<String, Object>>();
    if (p.getLoanId() != null) {
      for (var v :
          valuations.findByOrganizationIdAndLoanIdOrderByValuedAtDescIdDesc(
              org, p.getLoanId(), PageRequest.of(0, 100))) {
        var data = json.tree(v.getSourceData());
        if (data.has("collateral"))
          for (var mark : data.get("collateral")) {
            if (mark.path("positionId").asLong() == id)
              history.add(
                  map(
                      "valuationId",
                      v.getId(),
                      "valuedAt",
                      v.getValuedAt(),
                      "price",
                      mark.get("price"),
                      "priceAt",
                      mark.get("priceAt"),
                      "marketValue",
                      mark.get("marketValue"),
                      "haircut",
                      mark.get("haircut"),
                      "eligibleValue",
                      mark.get("recognizedValue")));
          }
      }
    }
    return map(
        "position",
        row,
        "movements",
        movements.findByOrganizationIdAndPositionIdOrderByCreatedAtDescIdDesc(org, id),
        "valuations",
        history,
        "historyWindow",
        "Latest 100 loan valuations; older or legacy snapshots may not include position marks",
        "asOf",
        clock.instant());
  }
}
