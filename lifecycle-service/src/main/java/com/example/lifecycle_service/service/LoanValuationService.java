package com.example.lifecycle_service.service;

import com.example.lifecycle_service.model.*;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class LoanValuationService {
  private final LoanRepository loanRepository;
  private final MslaRepository mslaRepository;
  private final FeeSecurityRepository feeSecurityRepository;
  private final CollateralPositionRepository collateralPositionRepository;
  private final LoanValuationRepository loanValuationRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  @Value("${lifecycle.lender-commands-topic:lender.lifecycle.commands.v1}")
  private String lenderCommandsTopic;

  @Value("${lifecycle.lender-events-topic:lender.lifecycle.events.v1}")
  private String lenderEventsTopic;

  @Transactional
  public LoanValuation runValuationForLoan(long loanId) {
    var loan =
        loanRepository
            .findById(loanId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found: " + loanId));
    var msla =
        mslaRepository
            .findById(loan.getMslaId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MSLA not found: " + loan.getMslaId()));

    BigDecimal marketValue = resolveLoanMarketValue(loan);
    BigDecimal recognizedCollateral = resolveRecognizedCollateral(loanId, marketValue);
    BigDecimal requiredCollateral = marketValue.multiply(safeZero(msla.getCoverageRatio()));
    BigDecimal coverageRatio =
        marketValue.signum() > 0 ? recognizedCollateral.divide(marketValue, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    BigDecimal shortfall = requiredCollateral.subtract(recognizedCollateral).max(BigDecimal.ZERO);

    var valuation = new LoanValuation();
    valuation.setOrganizationId(loan.getOrganizationId());
    valuation.setConnectionId(loan.getConnectionId());
    valuation.setLoanId(loan.getId());
    valuation.setOwnerReferenceId(loan.getId());
    valuation.setStatus("REPORTED");
    valuation.setCreatedAt(Instant.now());
    valuation.setValuedAt(Instant.now());
    valuation.setSourceVersion(nextSourceVersion(loanId));
    valuation.setSourceData("{\"type\":\"RUN_LOAN_VALUATION\"}");
    valuation.setLoanMarketValue(marketValue.setScale(6, RoundingMode.HALF_UP));
    valuation.setRequiredCollateral(requiredCollateral.setScale(6, RoundingMode.HALF_UP));
    valuation.setRecognizedCollateral(recognizedCollateral.setScale(6, RoundingMode.HALF_UP));
    valuation.setCoverageRatio(coverageRatio.setScale(6, RoundingMode.HALF_UP));
    valuation.setShortfall(shortfall.setScale(6, RoundingMode.HALF_UP));

    valuation = loanValuationRepository.save(valuation);
    publishValuationEvent(valuation);
    return valuation;
  }

  @Transactional(readOnly = true)
  public LoanValuation latestForLoan(long loanId) {
    return loanValuationRepository
        .findFirstByLoanIdOrderByValuedAtDescIdDesc(loanId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No valuation found for loan: " + loanId));
  }

  @Transactional
  public void requestValuationRun(long loanId) {
    var loan =
        loanRepository
            .findById(loanId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found: " + loanId));
    publishCommand(
        loan.getOrganizationId(),
        loanId,
        "RUN_LOAN_VALUATION",
        Map.of("loanId", loanId));
  }

  @Transactional
  public void handleRunValuationCommand(long loanId) {
    runValuationForLoan(loanId);
  }

  private BigDecimal resolveLoanMarketValue(Loan loan) {
    long quantity = loan.getOutstandingQuantity() != null ? loan.getOutstandingQuantity() : 0L;
    if (quantity <= 0) return BigDecimal.ZERO;

    var security =
        feeSecurityRepository
            .findById(loan.getSecurityId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "Loan security is not loaded in lifecycle market data: " + loan.getSecurityId()));
    BigDecimal price = safeZero(security.getPrice());
    return price.multiply(BigDecimal.valueOf(quantity)).setScale(6, RoundingMode.HALF_UP);
  }

  private BigDecimal resolveRecognizedCollateral(long loanId, BigDecimal marketValue) {
    List<CollateralPosition> positions = collateralPositionRepository.findByLoanId(loanId);
    BigDecimal recognized = BigDecimal.ZERO;
    for (var position : positions) {
      if (!"POSTED".equalsIgnoreCase(position.getStatus())) {
        continue;
      }
      if ("CASH".equalsIgnoreCase(position.getAssetType())) {
        recognized = recognized.add(safeZero(position.getCashAmount()));
      } else if (position.getSecurityId() != null && position.getQuantity() != null && position.getQuantity() > 0) {
        long positionQuantity = position.getQuantity();
        long valuationQuantity = Math.max(positionQuantity, 1L);
        BigDecimal unitPrice = BigDecimal.ZERO;
        if (marketValue.signum() > 0) {
          unitPrice =
              marketValue.divide(BigDecimal.valueOf(valuationQuantity), 8, RoundingMode.HALF_UP);
        }
        BigDecimal haircut = safeZero(position.getHaircut());
        BigDecimal valuation =
            unitPrice.multiply(BigDecimal.valueOf(positionQuantity))
                .multiply(BigDecimal.ONE.subtract(haircut));
        recognized = recognized.add(valuation);
      }
    }
    return recognized.setScale(6, RoundingMode.HALF_UP);
  }

  private void publishCommand(long organizationId, long referenceId, String type, Map<String, Object> data) {
    try {
      var payloadMap = new LinkedHashMap<String, Object>();
      payloadMap.put("eventId", UUID.randomUUID().toString());
      payloadMap.put("type", type);
      payloadMap.put("organizationId", organizationId);
      payloadMap.put("referenceId", referenceId);
      payloadMap.put("occurredAt", Instant.now());
      payloadMap.put("data", data);

      String payload = objectMapper.writeValueAsString(payloadMap);
      kafkaTemplate.send(new ProducerRecord<>(lenderCommandsTopic, String.valueOf(organizationId), payload));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to publish lifecycle valuation command", ex);
    }
  }

  private void publishValuationEvent(LoanValuation valuation) {
    try {
      var data = new LinkedHashMap<String, Object>();
      data.put("loanId", valuation.getLoanId());
      data.put("loanMarketValue", valuation.getLoanMarketValue());
      data.put("requiredCollateral", valuation.getRequiredCollateral());
      data.put("recognizedCollateral", valuation.getRecognizedCollateral());
      data.put("coverageRatio", valuation.getCoverageRatio());
      data.put("shortfall", valuation.getShortfall());

      var payloadMap = new LinkedHashMap<String, Object>();
      payloadMap.put("eventId", UUID.randomUUID().toString());
      payloadMap.put("type", "VALUATION_REPORTED");
      payloadMap.put("organizationId", valuation.getOrganizationId());
      payloadMap.put("referenceId", valuation.getId() != null ? valuation.getId() : valuation.getOwnerReferenceId());
      payloadMap.put("version", valuation.getSourceVersion());
      payloadMap.put("occurredAt", valuation.getValuedAt());
      payloadMap.put("data", data);

      String payload = objectMapper.writeValueAsString(payloadMap);
      kafkaTemplate.send(new ProducerRecord<>(lenderEventsTopic, String.valueOf(valuation.getOrganizationId()), payload));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to publish valuation event", ex);
    }
  }

  private long nextSourceVersion(long loanId) {
    return loanValuationRepository.findFirstByLoanIdOrderByValuedAtDescIdDesc(loanId)
        .map(existing -> existing.getSourceVersion() + 1L)
        .orElse(1L);
  }

  private BigDecimal safeZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
