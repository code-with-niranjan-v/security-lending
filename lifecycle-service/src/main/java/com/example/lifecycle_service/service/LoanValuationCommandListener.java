package com.example.lifecycle_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "lifecycle.kafka-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LoanValuationCommandListener {
  private final ObjectMapper json;
  private final LoanValuationService loanValuationService;

  @KafkaListener(
      topics = "${lifecycle.lender-commands-topic:lender.lifecycle.commands.v1}",
      groupId = "lifecycle-loan-valuation")
  public void receive(String body) throws JsonProcessingException {
    var command = json.readTree(body);
    if("VALUATION_REQUESTED".equals(command.path("type").asText())){
      long org=command.path("organizationId").asLong(),request=command.path("referenceId").asLong();
      if(org<=0||request<=0)throw new IllegalArgumentException("Invalid valuation request IDs");
      loanValuationService.processRequest(org,request);return;
    }
    if (!"RUN_LOAN_VALUATION".equals(command.path("type").asText())) return;

    long loanId = command.path("data").path("loanId").asLong();
    if (loanId <= 0) throw new IllegalArgumentException("Invalid loan valuation command");

    loanValuationService.handleRunValuationCommand(loanId);
  }
}
