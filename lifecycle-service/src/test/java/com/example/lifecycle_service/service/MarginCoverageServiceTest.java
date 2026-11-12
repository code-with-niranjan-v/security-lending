package com.example.lifecycle_service.service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.lifecycle_service.model.MarginCoverageDecision;
import com.example.lifecycle_service.repository.CollateralPositionRepository;
import com.example.lifecycle_service.repository.LoanRepository;
import com.example.lifecycle_service.repository.LoanValuationRepository;
import com.example.lifecycle_service.repository.MarginCallRepository;
import com.example.lifecycle_service.repository.MslaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class MarginCoverageServiceTest {

  private final MarginCoverageService service =
      new MarginCoverageService(
          null,
          null,
          null,
          null,
          null,
          null,
          (KafkaTemplate<String, String>) null,
          new ObjectMapper().registerModule(new JavaTimeModule()));

  @Test
  void shouldTriggerMarginCallWhenCoverageFallsBelowPolicy() {
    MarginCoverageDecision decision =
        service.evaluate(
            100L,
            10L,
            new BigDecimal("1000"),
            new BigDecimal("900"),
            new BigDecimal("1.15"),
            BigDecimal.ZERO);

    assertTrue(decision.requiresMarginCall());
    assertEquals(new BigDecimal("1150.00"), decision.getRequiredCollateral());
    assertEquals(new BigDecimal("250.00"), decision.getShortfall());
    assertEquals(new BigDecimal("250.00"), decision.getMarginCallAmount());
  }

  @Test
  void shouldNotTriggerMarginCallWhenCoverageIsHealthy() {
    MarginCoverageDecision decision =
        service.evaluate(
            100L,
            10L,
            new BigDecimal("1000"),
            new BigDecimal("1200"),
            new BigDecimal("1.15"),
            BigDecimal.ZERO);

    assertFalse(decision.requiresMarginCall());
    assertEquals(new BigDecimal("1150.00"), decision.getRequiredCollateral());
    assertEquals(BigDecimal.ZERO, decision.getShortfall());
    assertEquals(BigDecimal.ZERO, decision.getMarginCallAmount());
  }
}
