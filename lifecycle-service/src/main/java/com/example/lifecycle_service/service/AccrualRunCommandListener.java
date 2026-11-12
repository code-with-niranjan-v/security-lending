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
public class AccrualRunCommandListener {
  private final ObjectMapper json;
  private final AccrualRunService runs;

  @KafkaListener(
      topics = "${lifecycle.lender-commands-topic:lender.lifecycle.commands.v1}",
      groupId = "lifecycle-accrual-runs")
  public void receive(String body) throws JsonProcessingException {
    var command = json.readTree(body);
    if (!java.util.Set.of("FEE_ACCRUAL_REQUESTED", "REBATE_ACCRUAL_REQUESTED")
        .contains(command.path("type").asText())) return;
    long org = command.path("organizationId").asLong(), id = command.path("referenceId").asLong();
    if (org <= 0 || id <= 0) throw new IllegalArgumentException("Invalid accrual request IDs");
    runs.process(org, id);
  }
}
