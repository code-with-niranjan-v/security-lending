package com.example.lender_service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.lender_service.dto.*;
import com.example.lender_service.repository.MslaRepository;
import com.example.lender_service.repository.OutboxEventRepository;
import com.example.lender_service.service.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "lender.kafka-enabled=true",
      "spring.datasource.url=jdbc:h2:mem:lender_kafka;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE",
      "logging.level.org.apache.kafka=ERROR",
      "logging.level.kafka=ERROR"
    })
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 1,
    topics = {
      "lender.commands.v1",
      "lender.commands.v1.DLT",
      "lender.lifecycle.events.v1",
      "lender.lifecycle.events.v1.DLT",
      "lender.confirmations.v1",
      "lender.events.v1",
      "lender.lifecycle.commands.v1"
    },
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaIntegrationTests {
  @Autowired EmbeddedKafkaBroker broker;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired DirectoryService directory;
  @Autowired BorrowerCommandService commands;
  @Autowired AgreementService decisions;
  @Autowired MslaRepository agreements;
  @Autowired JsonService json;
  @Autowired OutboxService outbox;
  @Autowired OutboxEventRepository outboxEvents;

  @Test
  void trustedAdapterCommandIsConsumedAndConfirmationIsPublishedWhileMissingProvenanceGoesToDlt()
      throws Exception {
    var org = directory.create(new OrganizationRequest("Kafka fund", "LENDER_KAFKA", 8800001));
    var c =
        directory.connect(
            org.getId(), new BorrowerConnectionRequest("BORROWER_DEMO", 1, "Kafka borrower"));
    var day = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    var proposal =
        new AgreementProposal(
            org.getPublicReferenceId(),
            "MSLA-KAFKA",
            day.minusDays(1),
            day.plusYears(1),
            new BigDecimal("1000000"),
            new BigDecimal("0.03"),
            new BigDecimal("0.01"),
            new BigDecimal("1.05"),
            "MONTHLY",
            365,
            true,
            24,
            48,
            List.of(new RuleRequest("CASH", null, BigDecimal.ZERO)));
    try (var confirmations = observer("confirmations", "lender.confirmations.v1");
        var dlt = observer("dlt", "lender.commands.v1.DLT")) {
      String payload = body(org.getExternalId(), proposal);
      var record = new ProducerRecord<String, String>("lender.commands.v1", "1", payload);
      record.headers().add("x-counterparty-id", "BORROWER_DEMO".getBytes(StandardCharsets.UTF_8));
      kafka.send(record).get(10, TimeUnit.SECONDS);
      waitFor(
          () -> agreements.findByConnectionIdAndBorrowerMslaId(c.getId(), 10L).isPresent(),
          "Kafka command was not ingested");
      var agreement = agreements.findByConnectionIdAndBorrowerMslaId(c.getId(), 10L).orElseThrow();
      var parent = SpanContext.createFromRemoteParent(
          "50cfae1b2ac64618b323221677f49931", "525e1a82c44ded37",
          TraceFlags.getSampled(), TraceState.getDefault());
      try (var scope = Span.wrap(parent).makeCurrent()) {
        decisions.decide(org.getId(), agreement.getId(), true, "Approved");
      }
      assertTrue(outboxEvents.findByOrganizationId(org.getId(), Pageable.unpaged()).stream()
          .anyMatch(event -> event.getPayload().contains("AGREEMENT_ACCEPTED")
              && event.getTraceParent() != null
              && event.getTraceParent().startsWith("00-" + parent.getTraceId() + "-")),
          "The queued confirmation must retain the original trace ID");
      for (int i = 0; i < 20 && !outbox.pending().isEmpty(); i++)
        for (var key : outbox.pending()) outbox.publishNext(key);
      boolean accepted = false;
      long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while (!accepted && System.nanoTime() < deadline)
        for (var r : confirmations.poll(Duration.ofMillis(200))) {
          var e = json.tree(r.value());
          if (e.path("type").asText().equals("AGREEMENT_ACCEPTED")) {
            assertEquals(10, e.path("referenceId").asLong());
            assertEquals(1, e.path("organizationId").asLong());
            assertEquals("BORROWER_DEMO", e.path("borrowerExternalId").asText());
            accepted = true;
          }
        }
      assertTrue(accepted, "Approval confirmation was not published");
      String untrusted = body(org.getExternalId(), proposal);
      kafka.send("lender.commands.v1", "1", untrusted).get(10, TimeUnit.SECONDS);
      var rejected =
          KafkaTestUtils.getSingleRecord(dlt, "lender.commands.v1.DLT", Duration.ofSeconds(20));
      assertEquals(untrusted, rejected.value());
      assertEquals(1, agreements.count());
    }
  }

  private String body(String lender, AgreementProposal proposal) {
    return json.write(
        new BorrowerCommand(
            UUID.randomUUID().toString(),
            "AGREEMENT_PROPOSED",
            1,
            10,
            Instant.now(),
            "COUNTERPARTY",
            10,
            lender,
            json.tree(json.write(proposal))));
  }

  private Consumer<String, String> observer(String name, String topic) {
    var props = KafkaTestUtils.consumerProps("lender-test-" + name, "false", broker);
    props.put("auto.offset.reset", "earliest");
    Consumer<String, String> consumer =
        new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
            .createConsumer();
    broker.consumeFromAnEmbeddedTopic(consumer, topic);
    return consumer;
  }

  private void waitFor(BooleanSupplier condition, String message) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(50);
    }
    fail(message);
  }
}
