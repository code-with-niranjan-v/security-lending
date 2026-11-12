package com.example.lifecycle_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.lifecycle_service.model.Loan;
import com.example.lifecycle_service.model.LoanValuation;
import com.example.lifecycle_service.model.MarginCall;
import com.example.lifecycle_service.model.Msla;
import com.example.lifecycle_service.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MarginCoverageServiceKafkaTest {
  @Mock private LoanRepository loanRepository;
  @Mock private MslaRepository mslaRepository;
  @Mock private CollateralPositionRepository collateralPositionRepository;
  @Mock private LoanValuationRepository loanValuationRepository;
  @Mock private MarginCallRepository marginCallRepository;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private MarginCoverageService service;

  @BeforeEach
  void setUp() {
    service = new MarginCoverageService(
        loanRepository,
        org.mockito.Mockito.mock(RecallRequestRecordRepository.class),
        mslaRepository,
        collateralPositionRepository,
        loanValuationRepository,
        marginCallRepository,
        kafkaTemplate,
        new ObjectMapper().registerModule(new JavaTimeModule()));
    ReflectionTestUtils.setField(service, "lenderEventsTopic", "lender.lifecycle.events.v1");
  }

  @Test
  void triggerMarginCallForLoanPublishesLifecycleEvent() throws Exception {
    Loan loan = new Loan();
    loan.setId(7L);
    loan.setOrganizationId(1L);
    loan.setConnectionId(19L);
    loan.setMslaId(5L);
    loan.setOutstandingQuantity(0L);
    loan.setStatus("ACTIVE");

    Msla msla = new Msla();
    msla.setId(5L);
    msla.setOrganizationId(1L);
    msla.setCoverageRatio(new BigDecimal("1.10"));

    LoanValuation valuation = new LoanValuation();
    valuation.setLoanMarketValue(new BigDecimal("1000"));

    when(loanRepository.findById(7L)).thenReturn(Optional.of(loan));
    when(mslaRepository.findById(5L)).thenReturn(Optional.of(msla));
    when(loanValuationRepository.findFirstByLoanIdOrderByValuedAtDescIdDesc(7L))
        .thenReturn(Optional.of(valuation));
    when(collateralPositionRepository.findByLoanId(7L)).thenReturn(List.of());
    when(marginCallRepository.save(any(MarginCall.class))).thenAnswer(invocation -> {
      MarginCall saved = invocation.getArgument(0);
      saved.setId(101L);
      return saved;
    });

    var decision = service.triggerMarginCallForLoan(7L, new BigDecimal("200"));

    assertEquals("MARGIN_CALL_ISSUED", decision.getStatus());
    verify(kafkaTemplate).send(any(ProducerRecord.class));
  }

  @Test
  void triggerMarginCallForLoanRefreshesExistingOpenMarginCall() throws Exception {
    Loan loan = new Loan();
    loan.setId(7L);
    loan.setOrganizationId(1L);
    loan.setConnectionId(19L);
    loan.setMslaId(5L);
    loan.setOutstandingQuantity(0L);
    loan.setStatus("ACTIVE");

    Msla msla = new Msla();
    msla.setId(5L);
    msla.setOrganizationId(1L);
    msla.setCoverageRatio(new BigDecimal("1.10"));

    LoanValuation valuation = new LoanValuation();
    valuation.setLoanMarketValue(new BigDecimal("1000"));

    MarginCall existingCall = new MarginCall();
    existingCall.setId(22L);
    existingCall.setOrganizationId(1L);
    existingCall.setConnectionId(19L);
    existingCall.setLoanId(7L);
    existingCall.setOwnerReferenceId(7L);
    existingCall.setSourceVersion(1L);
    existingCall.setSourceData("{\"type\":\"AUTO_MARGIN_CALL\"}");
    existingCall.setStatus("PARTIALLY_FULFILLED");
    existingCall.setCreatedAt(Instant.now());
    existingCall.setAmount(new BigDecimal("150"));
    existingCall.setFulfilledAmount(new BigDecimal("50"));
    existingCall.setDueAt(Instant.now().plusSeconds(60));
    existingCall.setReason("Coverage ratio below the MSLA threshold.");

    when(loanRepository.findById(7L)).thenReturn(Optional.of(loan));
    when(mslaRepository.findById(5L)).thenReturn(Optional.of(msla));
    when(loanValuationRepository.findFirstByLoanIdOrderByValuedAtDescIdDesc(7L))
        .thenReturn(Optional.of(valuation));
    when(collateralPositionRepository.findByLoanId(7L)).thenReturn(List.of());
    when(marginCallRepository.findFirstByOrganizationIdAndOwnerReferenceIdOrderByCreatedAtDesc(1L, 7L))
        .thenReturn(Optional.of(existingCall));
    when(marginCallRepository.save(any(MarginCall.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var decision = service.triggerMarginCallForLoan(7L, new BigDecimal("200"));

    assertEquals("MARGIN_CALL_REFRESHED", decision.getStatus());
    assertEquals("PARTIALLY_FULFILLED", existingCall.getStatus());
    assertEquals(new BigDecimal("50"), existingCall.getFulfilledAmount());
    assertEquals(new BigDecimal("250"), existingCall.getAmount());
    verify(kafkaTemplate).send(any(ProducerRecord.class));
  }
}
