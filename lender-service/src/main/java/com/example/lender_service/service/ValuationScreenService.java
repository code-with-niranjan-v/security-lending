package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.*;
import static com.example.lender_service.service.LoanScreenService.map;

import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import com.example.lender_service.response.PageResponse;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ValuationScreenService {
  private final LoanScreenService loanScreens;
  private final LoanRepository loans;
  private final LoanValuationRepository valuations;
  private final LifecycleRequestRepository requests;
  private final JsonService json;
  private final Clock clock;

  public Map<String, Object> overview(
      long org,
      int page,
      int size,
      Long connectionId,
      Long mslaId,
      String status,
      String coverage,
      String q) {
    return loanScreens.overview(org, page, size, connectionId, mslaId, status, coverage, q);
  }

  Map<String, Object> snapshot(LoanValuation v) {
    var data = json.tree(v.getSourceData());
    return map(
        "id",
        v.getId(),
        "loanId",
        v.getLoanId(),
        "valuedAt",
        v.getValuedAt(),
        "marketValue",
        v.getLoanMarketValue(),
        "eligibleCollateral",
        v.getRecognizedCollateral(),
        "requiredCollateral",
        v.getRequiredCollateral(),
        "coverage",
        v.getCoverageRatio().multiply(java.math.BigDecimal.valueOf(100)),
        "shortfall",
        v.getShortfall(),
        "status",
        v.getStatus(),
        "price",
        data.hasNonNull("price") ? data.get("price").decimalValue() : null,
        "priceAt",
        data.hasNonNull("priceAt") ? data.get("priceAt").asText() : null,
        "quantity",
        data.hasNonNull("quantity") ? data.get("quantity").asLong() : null,
        "collateral",
        data.has("collateral") ? data.get("collateral") : List.of());
  }

  public Map<String, Object> detail(long org, long loanId, int page, int size) {
    found(loans.findByIdAndOrganizationId(loanId, org));
    if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid paging");
    var history =
        valuations.findByOrganizationIdAndLoanIdOrderByValuedAtDescIdDesc(
            org, loanId, PageRequest.of(page, size));
    var context = loanScreens.detail(org, loanId);
    return map(
        "loan",
        context.get("loan"),
        "history",
        new PageResponse<>(
            history.getContent().stream().map(this::snapshot).toList(),
            page,
            size,
            history.getTotalElements(),
            history.getTotalPages()),
        "runs",
        requests.findByOrganizationIdAndLoanIdIn(org, List.of(loanId)).stream()
            .filter(x -> "VALUATION_REQUESTED".equals(x.getType()))
            .sorted(Comparator.comparing(LifecycleRequest::getId).reversed())
            .limit(50)
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
        "status",
        r.getStatus(),
        "createdAt",
        r.getCreatedAt(),
        "failureReason",
        payload.hasNonNull("failureReason") ? payload.get("failureReason").asText() : null);
  }

  public Map<String, Object> run(long org, long id) {
    var r = found(requests.findByIdAndOrganizationId(id, org));
    require("VALUATION_REQUESTED".equals(r.getType()), "Not a valuation run");
    return run(r);
  }
}
