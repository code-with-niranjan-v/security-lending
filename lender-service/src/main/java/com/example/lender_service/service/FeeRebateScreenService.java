package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;
import static com.example.lender_service.service.LoanScreenService.map;

import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import com.example.lender_service.response.PageResponse;
import java.math.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
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
public class FeeRebateScreenService {
  private final DailyAccrualRepository accruals;
  private final LendingFeeRepository fees;
  private final RebateRepository rebates;
  private final LoanRepository loans;
  private final MslaRepository agreements;
  private final BorrowerConnectionRepository borrowers;
  private final SecurityMasterRepository securities;
  private final LifecycleRequestRepository requests;
  private final TenantService tenants;
  private final JsonService json;
  private final Clock clock;
  private static final Set<String> RUN_TYPES =
      Set.of("FEE_ACCRUAL_REQUESTED", "REBATE_ACCRUAL_REQUESTED");

  private LocalDate today() {
    return clock.instant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
  }

  private LocalDate date(Instant value) {
    return value == null ? null : value.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
  }

  private <T> Map<Long, T> index(List<T> values, Function<T, Long> key) {
    return values.stream().collect(Collectors.toMap(key, Function.identity()));
  }

  private String rule(Msla a, String kind) {
    return kind.equals("FEE") ? a.getBillingFrequency() : a.getRebateSettlementFrequency();
  }

  private LocalDate due(Loan l, Msla a, String kind, LocalDate entry) {
    String r = rule(a, kind);
    LocalDate closed = date(l.getClosedAt());
    if (r.equals("AT_CLOSE")) return closed;
    LocalDate end =
        switch (r) {
          case "DAILY" -> entry;
          case "WEEKLY" -> entry.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
          default -> entry.with(TemporalAdjusters.lastDayOfMonth());
        };
    if (closed != null && !closed.isAfter(end)) return closed;
    return end.plusDays(1);
  }

  private Map<String, Object> common(
      long id,
      String category,
      String kind,
      Loan l,
      Msla a,
      BorrowerConnection b,
      SecurityMaster s) {
    return map(
        "id",
        id,
        "key",
        category + ":" + id,
        "category",
        category,
        "loanId",
        l.getId(),
        "borrower",
        b.getName(),
        "symbol",
        s.getSymbol(),
        "currency",
        s.getCurrency(),
        "mslaId",
        a.getId(),
        "agreementNumber",
        a.getNumber(),
        "type",
        kind,
        "settlementRule",
        rule(a, kind),
        "dayBasis",
        a.getDayBasis());
  }

  public Map<String, Object> overview(
      long org, int page, int size, String tab, Long loanId, String q) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid paging");
    if (!Set.of("ALL", "FEE", "REBATE", "DUE").contains(tab))
      throw new IllegalArgumentException("Unknown fees/rebates tab");
    var allLoans = loans.findByOrganizationId(org, Pageable.unpaged()).getContent();
    var ll = index(allLoans, Loan::getId);
    var aa =
        index(
            agreements.findAllById(allLoans.stream().map(Loan::getMslaId).distinct().toList()),
            Msla::getId);
    var bb =
        index(
            borrowers.findAllById(allLoans.stream().map(Loan::getConnectionId).distinct().toList()),
            BorrowerConnection::getId);
    var ss =
        index(
            securities.findAllById(allLoans.stream().map(Loan::getSecurityId).distinct().toList()),
            SecurityMaster::getId);
    var daily =
        accruals
            .findByOrganizationId(org, Pageable.unpaged(Sort.by(Sort.Direction.DESC, "id")))
            .getContent();
    var bills = fees.findByOrganizationId(org, Pageable.unpaged()).getContent();
    var rb = rebates.findByOrganizationId(org, Pageable.unpaged()).getContent();
    var runs =
        requests.findByOrganizationId(org, Pageable.unpaged()).getContent().stream()
            .filter(x -> RUN_TYPES.contains(x.getType()))
            .toList();
    var totalsByLoan = new HashMap<String, BigDecimal>();
    for (var d : daily)
      totalsByLoan.merge(d.getLoanId() + ":" + d.getAccrualType(), d.getAmount(), BigDecimal::add);
    var records = new ArrayList<Map<String, Object>>();
    for (var d : daily) {
      var l = ll.get(d.getLoanId());
      var a = aa.get(l.getMslaId());
      var row =
          common(
              d.getId(),
              "accruals",
              d.getAccrualType(),
              l,
              a,
              bb.get(l.getConnectionId()),
              ss.get(l.getSecurityId()));
      LocalDate due = due(l, a, d.getAccrualType(), d.getEntryDate());
      String settlementStatus = "Not issued";
      Long settlementId = null;
      if (d.getAccrualType().equals("FEE")) {
        var bill =
            bills.stream()
                .filter(
                    x ->
                        x.getLoanId().equals(l.getId())
                            && !d.getEntryDate().isBefore(x.getPeriodStart())
                            && !d.getEntryDate().isAfter(x.getPeriodEnd()))
                .findFirst();
        if (bill.isPresent()) {
          due = bill.get().getDueDate();
          settlementStatus = bill.get().getStatus();
          settlementId = bill.get().getId();
        }
      } else {
        var bill =
            rb.stream()
                .filter(
                    x ->
                        x.getLoanId().equals(l.getId())
                            && x.getPeriodStart() != null
                            && x.getPeriodEnd() != null
                            && !d.getEntryDate().isBefore(x.getPeriodStart())
                            && !d.getEntryDate().isAfter(x.getPeriodEnd()))
                .findFirst();
        if (bill.isPresent()) {
          due = bill.get().getPaymentDate();
          settlementStatus = bill.get().getStatus();
          settlementId = bill.get().getId();
        }
      }
      row.putAll(
          map(
              "entryDate",
              d.getEntryDate(),
              "rateBps",
              d.getRate().multiply(BigDecimal.valueOf(10000)),
              "amount",
              d.getAmount(),
              "baseValue",
              d.getBaseValue(),
              "accruedTotal",
              totalsByLoan.get(l.getId() + ":" + d.getAccrualType()),
              "dueDate",
              due,
              "status",
              d.getStatus(),
              "settlementStatus",
              settlementStatus,
              "settlementId",
              settlementId));
      records.add(row);
    }
    var dueRows = new ArrayList<Map<String, Object>>();
    for (var f : bills) {
      if (!"DUE".equals(f.getStatus())) continue;
      var l = ll.get(f.getLoanId());
      var row =
          common(
              f.getId(),
              "fees",
              "FEE",
              l,
              aa.get(l.getMslaId()),
              bb.get(l.getConnectionId()),
              ss.get(l.getSecurityId()));
      row.putAll(
          map(
              "entryDate",
              f.getPeriodEnd(),
              "periodStart",
              f.getPeriodStart(),
              "periodEnd",
              f.getPeriodEnd(),
              "rateBps",
              f.getRate().multiply(BigDecimal.valueOf(10000)),
              "amount",
              f.getAmount(),
              "accruedTotal",
              totalsByLoan.get(l.getId() + ":FEE"),
              "dueDate",
              f.getDueDate(),
              "status",
              f.getStatus(),
              "settlementStatus",
              f.getStatus()));
      dueRows.add(row);
    }
    for (var f : rb) {
      if (!"ACCRUED".equals(f.getStatus())) continue;
      var l = ll.get(f.getLoanId());
      var row =
          common(
              f.getId(),
              "rebates",
              "REBATE",
              l,
              aa.get(l.getMslaId()),
              bb.get(l.getConnectionId()),
              ss.get(l.getSecurityId()));
      row.putAll(
          map(
              "entryDate",
              f.getAccrualDate(),
              "periodStart",
              f.getPeriodStart(),
              "periodEnd",
              f.getPeriodEnd(),
              "rateBps",
              f.getRate().multiply(BigDecimal.valueOf(10000)),
              "amount",
              f.getAmount(),
              "accruedTotal",
              totalsByLoan.get(l.getId() + ":REBATE"),
              "dueDate",
              f.getPaymentDate(),
              "status",
              f.getStatus(),
              "settlementStatus",
              f.getStatus()));
      dueRows.add(row);
    }
    String search = q == null ? "" : q.toLowerCase(Locale.ROOT);
    java.util.function.Predicate<Map<String, Object>> matches =
        x ->
            (loanId == null || loanId.equals(x.get("loanId")))
                && (search.isBlank()
                    || (x.get("borrower")
                            + " "
                            + x.get("symbol")
                            + " "
                            + x.get("agreementNumber")
                            + " LOAN-"
                            + String.format("%06d", x.get("loanId")))
                        .toLowerCase(Locale.ROOT)
                        .contains(search));
    var scoped = records.stream().filter(matches).toList();
    var pending =
        dueRows.stream()
            .filter(matches)
            .sorted(Comparator.comparing(x -> (LocalDate) x.get("dueDate")))
            .toList();
    var filtered =
        tab.equals("DUE")
            ? pending
            : scoped.stream().filter(x -> tab.equals("ALL") || tab.equals(x.get("type"))).toList();
    var stats = new TreeMap<String, Map<String, Object>>();
    for (var row : scoped) {
      String currency = (String) row.get("currency");
      var total =
          stats.computeIfAbsent(
              currency,
              k ->
                  map(
                      "currency",
                      k,
                      "feeToday",
                      BigDecimal.ZERO,
                      "rebateToday",
                      BigDecimal.ZERO,
                      "feeTotal",
                      BigDecimal.ZERO,
                      "rebateTotal",
                      BigDecimal.ZERO));
      String prefix = row.get("type").equals("FEE") ? "fee" : "rebate";
      total.put(
          prefix + "Total",
          ((BigDecimal) total.get(prefix + "Total")).add((BigDecimal) row.get("amount")));
      if (today().equals(row.get("entryDate")))
        total.put(
            prefix + "Today",
            ((BigDecimal) total.get(prefix + "Today")).add((BigDecimal) row.get("amount")));
    }
    var eligible = new ArrayList<Map<String, Object>>();
    for (var l : allLoans) {
      var a = aa.get(l.getMslaId());
      var b = bb.get(l.getConnectionId());
      var s = ss.get(l.getSecurityId());
      var context =
          map(
              "loanId",
              l.getId(),
              "borrower",
              b.getName(),
              "symbol",
              s.getSymbol(),
              "agreementNumber",
              a.getNumber());
      if (!matches.test(context)) continue;
      if (l.getSettledAt() == null
          || l.getSettledAt().isAfter(clock.instant())
          || !Set.of("ACTIVE", "CLOSED").contains(l.getStatus())) continue;
      for (String kind : List.of("FEE", "REBATE")) {
        if ((kind.equals("FEE") ? a.getFeeRate() : a.getRebateRate()).signum() <= 0) continue;
        if (kind.equals("FEE") && l.isFeeFinalized()) continue;
        var pendingRun =
            runs.stream()
                .filter(
                    x ->
                        x.getLoanId().equals(l.getId())
                            && x.getType().equals(kind + "_ACCRUAL_REQUESTED")
                            && Set.of("PENDING", "ACCEPTED").contains(x.getStatus()))
                .findFirst();
        eligible.add(
            map(
                "loanId",
                l.getId(),
                "kind",
                kind,
                "borrower",
                b.getName(),
                "symbol",
                s.getSymbol(),
                "processedThrough",
                kind.equals("FEE") ? l.getFeeProcessedThrough() : l.getRebateProcessedThrough(),
                "pendingRunId",
                pendingRun.map(LifecycleRequest::getId).orElse(null)));
      }
    }
    long start = (long) page * size;
    return map(
        "records",
        new PageResponse<>(
            filtered.subList(
                (int) Math.min(start, filtered.size()),
                (int) Math.min(start + size, filtered.size())),
            page,
            size,
            filtered.size(),
            (int) Math.ceil((double) filtered.size() / size)),
        "counts",
        map(
            "ALL",
            scoped.size(),
            "FEE",
            scoped.stream().filter(x -> "FEE".equals(x.get("type"))).count(),
            "REBATE",
            scoped.stream().filter(x -> "REBATE".equals(x.get("type"))).count(),
            "DUE",
            pending.size()),
        "totals",
        stats.values(),
        "businessDate",
        today(),
        "businessZone",
        "Asia/Kolkata",
        "eligibleLoans",
        eligible,
        "loans",
        allLoans.stream()
            .map(
                l ->
                    map(
                        "id",
                        l.getId(),
                        "label",
                        String.format("LOAN-%06d", l.getId())
                            + " · "
                            + bb.get(l.getConnectionId()).getName()))
            .toList(),
        "runs",
        runs.stream()
            .filter(x -> loanId == null || loanId.equals(x.getLoanId()))
            .sorted(Comparator.comparing(LifecycleRequest::getId).reversed())
            .limit(30)
            .map(this::run)
            .toList(),
        "asOf",
        clock.instant());
  }

  public Map<String, Object> run(LifecycleRequest r) {
    var payload = json.tree(r.getPayload());
    return map(
        "id",
        r.getId(),
        "loanId",
        r.getLoanId(),
        "kind",
        r.getType().startsWith("FEE") ? "FEE" : "REBATE",
        "status",
        r.getStatus(),
        "createdAt",
        r.getCreatedAt(),
        "failureReason",
        payload.hasNonNull("failureReason") ? payload.get("failureReason").asText() : null,
        "result",
        payload.hasNonNull("result") ? payload.get("result") : null);
  }

  public Map<String, Object> run(long org, long id) {
    var r = found(requests.findByIdAndOrganizationId(id, org));
    require(RUN_TYPES.contains(r.getType()), "Not an accrual run");
    return run(r);
  }
}
