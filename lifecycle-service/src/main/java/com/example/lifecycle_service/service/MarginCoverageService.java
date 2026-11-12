package com.example.lifecycle_service.service;

import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarginCoverageService {
  private final LoanRepository loanRepository;
  private final RecallRequestRecordRepository lenderRequests;
  private final MslaRepository mslaRepository;
  private final CollateralPositionRepository collateralPositionRepository;
  private final LoanValuationRepository loanValuationRepository;
  private final MarginCallRepository marginCallRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  @Value("${lifecycle.lender-events-topic:lender.lifecycle.events.v1}")
  private String lenderEventsTopic;

  public MarginCoverageDecision evaluate(
      long organizationId,
      long loanId,
      BigDecimal loanMarketValue,
      BigDecimal recognizedCollateral,
      BigDecimal coverageRatio,
      BigDecimal marginBuffer) {
    BigDecimal value = safeZero(loanMarketValue);
    BigDecimal recognized = safeZero(recognizedCollateral);
    BigDecimal ratio = safeZero(coverageRatio);
    BigDecimal buffer = safeZero(marginBuffer);

    BigDecimal requiredCollateral = value.multiply(ratio).add(buffer);
    BigDecimal shortfall = requiredCollateral.subtract(recognized).max(BigDecimal.ZERO);
    BigDecimal effectiveCoverage =
        value.signum() > 0 ? recognized.divide(value, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;

    MarginCoverageDecision decision = new MarginCoverageDecision();
    decision.setLoanId(loanId);
    decision.setOrganizationId(organizationId);
    decision.setLoanMarketValue(value);
    decision.setRecognizedCollateral(recognized);
    decision.setRequiredCollateral(requiredCollateral);
    decision.setCoverageRatio(effectiveCoverage);
    decision.setShortfall(shortfall);
    decision.setMarginCallAmount(shortfall);
    decision.setStatus(shortfall.signum() > 0 ? "MARGIN_CALL_REQUIRED" : "HEALTHY");
    decision.setReason(
        shortfall.signum() > 0
            ? "Coverage ratio below the MSLA threshold."
            : "Coverage ratio remains within the MSLA threshold.");
    decision.setEvaluatedAt(Instant.now());
    return decision;
  }

  public MarginCoverageDecision evaluateLoan(long loanId) {
    var loan =
        loanRepository
            .findById(loanId)
            .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));
    var msla =
        mslaRepository
            .findById(loan.getMslaId())
            .orElseThrow(() -> new IllegalArgumentException("MSLA not found: " + loan.getMslaId()));

    BigDecimal loanMarketValue = resolveLoanMarketValue(loanId, loan);
    BigDecimal recognizedCollateral = resolveRecognizedCollateral(loanId, loanMarketValue, loan);

    return evaluate(
        loan.getOrganizationId(),
        loanId,
        loanMarketValue,
        recognizedCollateral,
        msla.getCoverageRatio(),
        BigDecimal.ZERO);
  }

  @Transactional
  public MarginCoverageDecision triggerMarginCallForLoan(long loanId, BigDecimal requestedAmount) {
    var loan =
        loanRepository
            .findById(loanId)
            .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));
    // Collateral settlement in the lender service locks this same organization row.
    loanRepository.lockOrganization(loan.getOrganizationId());
    MarginCoverageDecision decision = evaluateLoan(loanId);
    if(!lenderRequests.findByLoanIdAndTypeAndStatusIn(loanId,"MARGIN_CALL_REQUESTED",java.util.Set.of("PENDING","ACCEPTED")).isEmpty()){
      decision.setStatus("MARGIN_REQUEST_PENDING");
      decision.setReason("An explicit lender margin request awaits owner confirmation.");
      return decision;
    }

    BigDecimal amountToCall =
        requestedAmount != null && requestedAmount.signum() > 0
            ? requestedAmount
            : decision.getMarginCallAmount();

    if (amountToCall != null && amountToCall.signum() > 0) {
      var existingCall =
          marginCallRepository
              .findFirstByOrganizationIdAndOwnerReferenceIdOrderByCreatedAtDesc(
                  loan.getOrganizationId(), loan.getId())
              .orElse(null);

      MarginCall call = existingCall;
      boolean isExistingOpenCall =
          existingCall != null
              && !"FULFILLED".equalsIgnoreCase(existingCall.getStatus())
              && !"CANCELLED".equalsIgnoreCase(existingCall.getStatus())
              && !"REJECTED".equalsIgnoreCase(existingCall.getStatus());

      if (call == null) {
        call = new MarginCall();
        call.setOrganizationId(loan.getOrganizationId());
        call.setConnectionId(loan.getConnectionId());
        call.setLoanId(loan.getId());
        call.setOwnerReferenceId(loan.getId());
        call.setSourceVersion(1L);
        call.setSourceData("{\"type\":\"AUTO_MARGIN_CALL\"}");
        call.setStatus("OPEN");
        call.setCreatedAt(Instant.now());
      }

      BigDecimal fulfilled = isExistingOpenCall ? call.getFulfilledAmount() : BigDecimal.ZERO;
      call.setAmount(fulfilled.add(amountToCall));
      call.setFulfilledAmount(fulfilled);
      call.setDueAt(Instant.now().plus(Duration.ofHours(24)));
      call.setReason("Coverage ratio below the MSLA threshold.");
      if (!isExistingOpenCall) {
        call.setSourceVersion(call.getSourceVersion() == null ? 1L : call.getSourceVersion() + 1L);
        call.setCreatedAt(Instant.now());
      }
      call.setStatus(fulfilled.signum() > 0 ? "PARTIALLY_FULFILLED" : "OPEN");
      marginCallRepository.save(call);
      publishLifecycleEvent(call);
      decision.setMarginCallAmount(amountToCall);
      decision.setStatus(isExistingOpenCall ? "MARGIN_CALL_REFRESHED" : "MARGIN_CALL_ISSUED");
      decision.setReason(
          isExistingOpenCall
              ? "Existing margin call refreshed for the loan."
              : "Margin call created for the specified loan.");
      decision.setEvaluatedAt(Instant.now());
    } else {
      decision.setStatus("NO_MARGIN_CALL_REQUIRED");
      decision.setReason("No margin call is required for this loan.");
      decision.setEvaluatedAt(Instant.now());
    }
    return decision;
  }

  private BigDecimal resolveLoanMarketValue(long loanId, Loan loan) {
    var latestValuation =
        loanValuationRepository.findFirstByLoanIdOrderByValuedAtDescIdDesc(loanId).orElse(null);
    if (latestValuation != null && latestValuation.getLoanMarketValue() != null) {
      return safeZero(latestValuation.getLoanMarketValue());
    }
    if (loan.getOutstandingQuantity() != null && loan.getOutstandingQuantity() > 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.ZERO;
  }

  private BigDecimal resolveRecognizedCollateral(long loanId, BigDecimal loanMarketValue, Loan loan) {
    List<CollateralPosition> positions = collateralPositionRepository.findByLoanId(loanId);
    BigDecimal recognized = BigDecimal.ZERO;
    for (var position : positions) {
      if (!"POSTED".equals(position.getStatus())) {
        continue;
      }
      if ("CASH".equals(position.getAssetType())) {
        recognized = recognized.add(safeZero(position.getCashAmount()));
      } else if (position.getSecurityId() != null && position.getQuantity() != null) {
        BigDecimal unitPrice =
            loanMarketValue.signum() > 0 && loan.getOutstandingQuantity() != null && loan.getOutstandingQuantity() > 0
                ? loanMarketValue.divide(BigDecimal.valueOf(loan.getOutstandingQuantity()), 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal positionValue = unitPrice.multiply(BigDecimal.valueOf(position.getQuantity()));
        BigDecimal haircut = safeZero(position.getHaircut());
        recognized = recognized.add(positionValue.multiply(BigDecimal.ONE.subtract(haircut)));
      }
    }
    return recognized;
  }

  private void publishLifecycleEvent(MarginCall call) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("eventId", UUID.randomUUID().toString());
    payload.put("type", "MARGIN_CALL_UPDATED");
    payload.put("organizationId", call.getOrganizationId());
    payload.put("referenceId", call.getOwnerReferenceId());
    payload.put("version", call.getSourceVersion());
    payload.put("occurredAt", Instant.now());
    payload.put(
        "data",
        Map.of(
            "loanId", call.getLoanId(),
            "externalId", "AUTO-MARGIN-" + call.getId(),
            "amount", call.getAmount(),
            "fulfilledAmount", call.getFulfilledAmount(),
            "reason", call.getReason(),
            "status", call.getStatus(),
            "dueAt", call.getDueAt()));
    try {
      String body = objectMapper.writeValueAsString(payload);
      kafkaTemplate.send(new ProducerRecord<>(lenderEventsTopic, String.valueOf(call.getOrganizationId()), body));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to publish lifecycle margin event", ex);
    }
  }

  private BigDecimal safeZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
