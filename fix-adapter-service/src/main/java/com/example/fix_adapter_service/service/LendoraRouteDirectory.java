package com.example.fix_adapter_service.service;

import com.example.fix_adapter_service.config.AdapterProperties;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Private registry resolves logical account identities; configured FIX sessions remain mandatory. */
@Service
public class LendoraRouteDirectory {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  public record Entry(
    String type,
    long organizationId,
    String externalId,
    String institutionId
  ) {}

  private final AdapterProperties properties;
  private final RestClient client;
  private List<Entry> cached = List.of();
  private Instant expires = Instant.EPOCH;

  public LendoraRouteDirectory(AdapterProperties properties) {
    this.properties = properties;
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(3000);
    client = RestClient.builder()
      .requestInterceptor((outboundRequest, outboundBody, execution) ->
        com.example.platform.DiscoveryHttp.execute(
          discovery,
          outboundRequest,
          outboundBody,
          execution
        )
      )
      .baseUrl(properties.getLendoraUrl())
      .requestFactory(factory)
      .build();
  }

  public synchronized List<Entry> entries() {
    if (!properties.isLendoraRoutingEnabled()) return List.of();
    if (
      properties.getLendoraKey().isBlank()
    ) throw new IllegalArgumentException(
      "Lendora routing requires LENDORA_PROVISIONING_KEY on the FIX adapters"
    );
    if (Instant.now().isBefore(expires)) return cached;
    try {
      var response = client
        .get()
        .uri("/internal/lendora/transport-organizations")
        .header("X-Lendora-Provisioning-Key", properties.getLendoraKey())
        .retrieve()
        .body(JsonNode.class);
      if (
        response == null ||
        !response.path("success").asBoolean() ||
        !response.path("data").isArray()
      ) throw new IllegalArgumentException(
        "Invalid transport directory response"
      );
      var result = new ArrayList<Entry>();
      for (var row : response.path("data")) {
        String type = row.path("type").asText(),
          external = row.path("externalId").asText(),
          institution = row.path("institutionId").asText();
        long org = row.path("organizationId").asLong();
        if (
          !Set.of("BORROWER", "LENDER").contains(type) ||
          org <= 0 ||
          external.isBlank() ||
          institution.isBlank()
        ) throw new IllegalArgumentException(
          "Invalid transport directory entry"
        );
        result.add(new Entry(type, org, external, institution));
      }
      cached = List.copyOf(result);
      expires = Instant.now().plusSeconds(properties.getLendoraCacheSeconds());
      return cached;
    } catch (org.springframework.web.client.RestClientException e) {
      throw new IllegalArgumentException(
        "Cannot verify registered FIX routing. Check lendora-service and the shared provisioning key",
        e
      );
    }
  }

  private synchronized List<Entry> freshEntries() {
    expires = Instant.EPOCH;
    return entries();
  }

  public Optional<Entry> external(String externalId, String type) {
    var found = entries()
      .stream()
      .filter(e -> e.type().equals(type) && e.externalId().equals(externalId))
      .findFirst();
    if (
      found.isPresent() || !properties.isLendoraRoutingEnabled()
    ) return found;
    return freshEntries()
      .stream()
      .filter(e -> e.type().equals(type) && e.externalId().equals(externalId))
      .findFirst();
  }

  public boolean borrower(long organizationId, String institutionId) {
    if (
      entries()
        .stream()
        .anyMatch(
          e ->
            e.type().equals("BORROWER") &&
            e.organizationId() == organizationId &&
            e.institutionId().equals(institutionId)
        )
    ) return true;
    return (
      properties.isLendoraRoutingEnabled() &&
      freshEntries()
        .stream()
        .anyMatch(
          e ->
            e.type().equals("BORROWER") &&
            e.organizationId() == organizationId &&
            e.institutionId().equals(institutionId)
        )
    );
  }
}
