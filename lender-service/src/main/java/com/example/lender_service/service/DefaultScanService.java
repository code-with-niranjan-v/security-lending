package com.example.lender_service.service;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class DefaultScanService {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  private final TenantService tenants;
  private final EventService events;
  private final RestClient client;
  private final String key;

  public DefaultScanService(
    TenantService tenants,
    EventService events,
    @Value("${lender.lifecycle-url:http://localhost:8085}") String url,
    @Value("${lendora.provisioning-key:}") String key
  ) {
    this.tenants = tenants;
    this.events = events;
    this.key = key;
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(60000);
    client = RestClient.builder()
      .requestInterceptor((outboundRequest, outboundBody, execution) ->
        com.example.platform.DiscoveryHttp.execute(
          discovery,
          outboundRequest,
          outboundBody,
          execution
        )
      )
      .baseUrl(url)
      .requestFactory(factory)
      .build();
  }

  public JsonNode run(long org) {
    tenants.exists(org);
    if (key.isBlank()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "Default scan is not configured. Set the shared LENDORA_PROVISIONING_KEY on lender-service and lifecycle-service."
    );
    try {
      var response = client
        .post()
        .uri("/internal/lifecycle/organizations/{org}/defaults/scan", org)
        .header("X-Lendora-Provisioning-Key", key)
        .body(Map.of())
        .retrieve()
        .body(JsonNode.class);
      if (
        response == null ||
        !response.path("success").asBoolean() ||
        !response.path("data").isObject() ||
        response.path("data").path("organizationId").asLong() != org
      ) throw new ResponseStatusException(
        HttpStatus.BAD_GATEWAY,
        "Invalid default scan response"
      );
      events.local(org, "DEFAULT_SCAN_COMPLETED", org, response.path("data"));
      return response.path("data");
    } catch (RestClientException e) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Default scan could not complete. Check lifecycle-service and the shared provisioning key; refresh cases before retrying.",
        e
      );
    }
  }
}
