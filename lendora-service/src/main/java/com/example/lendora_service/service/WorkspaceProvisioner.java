package com.example.lendora_service.service;

import com.example.lendora_service.dto.AuthContracts.ProvisionRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class WorkspaceProvisioner {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  private final String lender, borrower, key;

  public WorkspaceProvisioner(
    @Value("${lendora.lender-url}") String lender,
    @Value("${lendora.borrower-url}") String borrower,
    @Value("${lendora.provisioning-key:}") String key
  ) {
    this.lender = lender;
    this.borrower = borrower;
    this.key = key;
  }

  public long provision(String type, ProvisionRequest request) {
    if (key.isBlank()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "Organization registration is not configured. Set the same LENDORA_PROVISIONING_KEY on all three backend services and restart them."
    );
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(10000);
    try {
      var response = RestClient.builder()
        .requestInterceptor((outboundRequest, outboundBody, execution) ->
          com.example.platform.DiscoveryHttp.execute(
            discovery,
            outboundRequest,
            outboundBody,
            execution
          )
        )
        .baseUrl(type.equals("BORROWER") ? borrower : lender)
        .requestFactory(factory)
        .build()
        .post()
        .uri("/internal/lendora/provision")
        .header("X-Lendora-Provisioning-Key", key)
        .body(request)
        .retrieve()
        .body(JsonNode.class);
      if (
        response == null ||
        !response.path("success").asBoolean() ||
        response.path("data").path("organizationId").asLong() <= 0
      ) throw new IllegalStateException(
        "Invalid workspace provisioning response"
      );
      return response.path("data").path("organizationId").asLong();
    } catch (HttpClientErrorException e) {
      if (
        e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403
      ) {
        throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Workspace provisioning was denied. Configure the same LENDORA_PROVISIONING_KEY on lendora-service, lender-service and borrower-service, then restart them.",
          e
        );
      }
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Workspace registration was rejected by the business service. Check its provisioning configuration.",
        e
      );
    } catch (ResourceAccessException e) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Cannot reach " +
          (type.equals("BORROWER") ? "borrower-service" : "lender-service") +
          ". Start the service and check the corresponding LENDORA_BORROWER_SERVICE_URL or LENDORA_LENDER_SERVICE_URL setting.",
        e
      );
    } catch (Exception e) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Your workspace could not be created. Please try again later.",
        e
      );
    }
  }
}
