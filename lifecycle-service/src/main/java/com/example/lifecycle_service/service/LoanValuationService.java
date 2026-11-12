package com.example.lifecycle_service.service;

import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Valuation owner: quoted security prices and confirmed positions, with a durable report. */
@Service
@RequiredArgsConstructor
public class LoanValuationService {
  private final LoanRepository loanRepository;
  private final MslaRepository mslaRepository;
  private final FeeSecurityRepository securities;
  private final CollateralPositionRepository positions;
  private final LoanValuationRepository valuations;
  private final LenderOutboxEventRepository outbox;
  private final RecallRequestRecordRepository requests;
  private final ObjectMapper json;

  @Value("${lifecycle.lender-commands-topic:lender.lifecycle.commands.v1}")
  private String commandTopic;

  @Value("${lifecycle.lender-events-topic:lender.lifecycle.events.v1}")
  private String eventTopic;

  private static BigDecimal amount(BigDecimal value) {
    return value.setScale(6, RoundingMode.HALF_UP);
  }

  private void require(boolean ok, String reason) {
    if (!ok) throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }

  private Loan lockLoan(long loanId) {
    long org =
        loanRepository
            .findOrganizationId(loanId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found"));
    require(loanRepository.lockOrganization(org) != null, "Organization not found");
    return loanRepository.findByIdAndOrganizationId(loanId, org).orElseThrow();
  }

  private FeeSecurity quote(long id) {
    var s =
        securities
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "Missing security " + id));
    require(
        s.getPrice() != null
            && s.getPrice().signum() > 0
            && s.getPriceAt() != null
            && !s.getPriceAt().isAfter(Instant.now()),
        "Missing or invalid cached price for security " + id);
    return s;
  }

  @Transactional
  public LoanValuation runValuationForLoan(long loanId) {
    return calculate(lockLoan(loanId), null, null);
  }

  @Transactional(readOnly = true)
  public LoanValuation latestForLoan(long id) {
    return valuations
        .findFirstByLoanIdOrderByValuedAtDescIdDesc(id)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No valuation found for loan " + id));
  }

  @Transactional
  public void requestValuationRun(long loanId) {
    var loan = lockLoan(loanId);
    enqueue(
        loan.getOrganizationId(),
        loanId,
        "RUN_LOAN_VALUATION",
        UUID.randomUUID().toString(),
        1,
        Map.of("loanId", loanId),
        commandTopic);
  }

  @Transactional
  public void handleRunValuationCommand(long loanId) {
    calculate(lockLoan(loanId), null, null);
  }

  @Transactional
  public void processRequest(long org, long requestId) {
    require(loanRepository.lockOrganization(org) != null, "Unknown organization");
    var request = requests.findByIdAndOrganizationId(requestId, org).orElseThrow();
    require("VALUATION_REQUESTED".equals(request.getType()), "Not a valuation request");
    String eventId = "valuation-run-" + requestId;
    if (!Set.of("PENDING", "ACCEPTED").contains(request.getStatus())
        || outbox.existsByEventId(eventId)) return;
    var loan = loanRepository.findByIdAndOrganizationId(request.getLoanId(), org).orElse(null);
    try {
      require(
          loan != null && Objects.equals(loan.getConnectionId(), request.getConnectionId()),
          "Loan no longer belongs to this request");
      calculate(loan, requestId, eventId);
    } catch (ResponseStatusException e) {
      enqueue(
          org,
          requestId,
          "LIFECYCLE_REQUEST_REJECTED",
          eventId,
          1,
          Map.of(
              "reason",
              Objects.requireNonNullElse(e.getReason(), "Valuation could not be calculated")),
          eventTopic);
    }
  }

  private LoanValuation calculate(Loan loan, Long requestId, String fixedEventId) {
    require(
        "ACTIVE".equals(loan.getStatus()) && loan.getOutstandingQuantity() > 0,
        "Valuation requires an active loan with outstanding securities");
    var agreement = mslaRepository.findById(loan.getMslaId()).orElseThrow();
    require(
        agreement.getOrganizationId().equals(loan.getOrganizationId())
            && agreement.getCoverageRatio() != null
            && agreement.getCoverageRatio().signum() > 0,
        "Invalid loan agreement");
    var security = quote(loan.getSecurityId());
    BigDecimal
        market =
            amount(security.getPrice().multiply(BigDecimal.valueOf(loan.getOutstandingQuantity()))),
        recognized = BigDecimal.ZERO;
    var marks = new ArrayList<Map<String, Object>>();
    for (var p : positions.findByLoanId(loan.getId())) {
      if (!Set.of("POSTED", "RELEASE_REQUESTED").contains(p.getStatus())) continue;
      require(
          p.getOrganizationId().equals(loan.getOrganizationId())
              && p.getConnectionId().equals(loan.getConnectionId()),
          "Collateral does not belong to this loan");
      require(
          p.getHaircut() != null
              && p.getHaircut().signum() >= 0
              && p.getHaircut().compareTo(BigDecimal.ONE) <= 0,
          "Invalid collateral haircut");
      BigDecimal price, value;
      Instant priceAt;
      String symbol;
      if ("CASH".equals(p.getAssetType())) {
        require(
            p.getCashAmount() != null && p.getCashAmount().signum() >= 0,
            "Invalid cash collateral");
        price = BigDecimal.ONE;
        value = p.getCashAmount();
        priceAt = Instant.now();
        symbol = "Cash";
      } else {
        require(
            p.getSecurityId() != null && p.getQuantity() != null && p.getQuantity() >= 0,
            "Invalid security collateral");
        var collateralSecurity = quote(p.getSecurityId());
        require(
            Objects.equals(security.getCurrency(), collateralSecurity.getCurrency()),
            "Collateral requires currency conversion that is not configured");
        price = collateralSecurity.getPrice();
        priceAt = collateralSecurity.getPriceAt();
        symbol = collateralSecurity.getSymbol();
        value = price.multiply(BigDecimal.valueOf(p.getQuantity()));
      }
      BigDecimal eligible = amount(value.multiply(BigDecimal.ONE.subtract(p.getHaircut())));
      recognized = recognized.add(eligible);
      marks.add(
          Map.of(
              "positionId",
              p.getId(),
              "assetType",
              p.getAssetType(),
              "symbol",
              symbol,
              "price",
              price,
              "priceAt",
              priceAt.toString(),
              "marketValue",
              amount(value),
              "haircut",
              p.getHaircut(),
              "recognizedValue",
              eligible,
              "status",
              p.getStatus()));
    }
    BigDecimal required = amount(market.multiply(agreement.getCoverageRatio())),
        shortfall = required.subtract(recognized).max(BigDecimal.ZERO),
        ratio = recognized.divide(market, 6, RoundingMode.HALF_UP);
    Instant now = Instant.now();
    long version =
        valuations
            .findFirstByLoanIdOrderByValuedAtDescIdDesc(loan.getId())
            .map(v -> v.getSourceVersion() + 1)
            .orElse(1L);
    long reference;
    do {
      reference = (UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE) | (1L << 62);
    } while (valuations.existsByOrganizationIdAndOwnerReferenceId(
        loan.getOrganizationId(), reference));
    var data = new LinkedHashMap<String, Object>();
    data.put("loanId", loan.getId());
    data.put("loanMarketValue", market);
    data.put("requiredCollateral", required);
    data.put("recognizedCollateral", recognized);
    data.put("coverageRatio", ratio);
    data.put("shortfall", shortfall);
    data.put("status", shortfall.signum() > 0 ? "SHORTFALL" : "SUFFICIENT");
    data.put("price", security.getPrice());
    data.put("priceAt", security.getPriceAt().toString());
    data.put("quantity", loan.getOutstandingQuantity());
    data.put("currency", security.getCurrency());
    data.put("collateral", marks);
    if (requestId != null) data.put("lifecycleRequestId", requestId);
    var v = new LoanValuation();
    v.setOrganizationId(loan.getOrganizationId());
    v.setConnectionId(loan.getConnectionId());
    v.setLoanId(loan.getId());
    v.setOwnerReferenceId(reference);
    v.setSourceVersion(version);
    v.setSourceData(write(data));
    v.setStatus((String) data.get("status"));
    v.setCreatedAt(now);
    v.setValuedAt(now);
    v.setLoanMarketValue(market);
    v.setRequiredCollateral(required);
    v.setRecognizedCollateral(amount(recognized));
    v.setCoverageRatio(ratio);
    v.setShortfall(amount(shortfall));
    valuations.save(v);
    enqueue(
        loan.getOrganizationId(),
        reference,
        "VALUATION_REPORTED",
        fixedEventId == null ? UUID.randomUUID().toString() : fixedEventId,
        version,
        data,
        eventTopic);
    return v;
  }

  private String write(Object data) {
    try {
      return json.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void enqueue(
      long org,
      long reference,
      String type,
      String eventId,
      long version,
      Object data,
      String topic) {
    Instant now = Instant.now();
    outbox.save(
        new LenderOutboxEvent(
            org,
            eventId,
            topic,
            write(
                Map.of(
                    "eventId",
                    eventId,
                    "organizationId",
                    org,
                    "referenceId",
                    reference,
                    "type",
                    type,
                    "version",
                    version,
                    "occurredAt",
                    now.toString(),
                    "data",
                    data)),
            now));
  }
}
