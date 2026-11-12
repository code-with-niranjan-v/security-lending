package com.example.lifecycle_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.lifecycle_service.model.*;
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
class LoanValuationServiceTest {
  @Mock private LoanRepository loanRepository;
  @Mock private MslaRepository mslaRepository;
  @Mock private FeeSecurityRepository feeSecurityRepository;
  @Mock private CollateralPositionRepository collateralPositionRepository;
  @Mock private LoanValuationRepository loanValuationRepository;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private LoanValuationService service;

  @BeforeEach
  void setUp() {
    service =
        new LoanValuationService(
            loanRepository,
            mslaRepository,
            feeSecurityRepository,
            collateralPositionRepository,
            loanValuationRepository,
            kafkaTemplate,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    ReflectionTestUtils.setField(service, "lenderCommandsTopic", "lender.lifecycle.commands.v1");
    ReflectionTestUtils.setField(service, "lenderEventsTopic", "lender.lifecycle.events.v1");
  }

  @Test
  void runValuationForLoanPersistsAndPublishesLifecycleEvent() throws Exception {
    Loan loan = new Loan();
    loan.setId(9L);
    loan.setOrganizationId(2L);
    loan.setConnectionId(11L);
    loan.setMslaId(4L);
    loan.setSecurityId(5L);
    loan.setOutstandingQuantity(10L);
    loan.setStatus("SETTLED");

    Msla msla = new Msla();
    msla.setId(4L);
    msla.setOrganizationId(2L);
    msla.setCoverageRatio(new BigDecimal("1.05"));

    var ctor = FeeSecurity.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    FeeSecurity security = (FeeSecurity) ctor.newInstance();
    org.springframework.test.util.ReflectionTestUtils.setField(security, "id", 5L);
    org.springframework.test.util.ReflectionTestUtils.setField(security, "symbol", "ABC");
    org.springframework.test.util.ReflectionTestUtils.setField(security, "exchange", "NSE");
    org.springframework.test.util.ReflectionTestUtils.setField(security, "price", new BigDecimal("100"));
    org.springframework.test.util.ReflectionTestUtils.setField(security, "priceAt", Instant.now());

    when(loanRepository.findById(9L)).thenReturn(Optional.of(loan));
    when(mslaRepository.findById(4L)).thenReturn(Optional.of(msla));
    when(feeSecurityRepository.findById(5L)).thenReturn(Optional.of(security));
    when(collateralPositionRepository.findByLoanId(9L)).thenReturn(List.of());
    when(loanValuationRepository.save(any(LoanValuation.class))).thenAnswer(inv -> inv.getArgument(0));

    LoanValuation valuation = service.runValuationForLoan(9L);

    assertEquals(new BigDecimal("1000.000000"), valuation.getLoanMarketValue());
    assertEquals(new BigDecimal("1050.000000"), valuation.getRequiredCollateral());
    assertEquals(new BigDecimal("0.000000"), valuation.getRecognizedCollateral());
    assertEquals(new BigDecimal("0.000000"), valuation.getCoverageRatio());
    verify(kafkaTemplate).send(any(ProducerRecord.class));
  }
}
