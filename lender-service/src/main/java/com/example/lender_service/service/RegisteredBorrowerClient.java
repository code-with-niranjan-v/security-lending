package com.example.lender_service.service;

import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class RegisteredBorrowerClient {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  public record BorrowerEntry(
    @Positive long directoryId,
    @Positive long borrowerOrganizationId,
    @NotBlank @Size(max = 160) String name,
    @NotBlank String registrationNumber,
    @NotBlank String country,
    @NotBlank String currency,
    @NotNull @Pattern(regexp = "BORROWER") String type
  ) {}

  private final RestClient client;
  private final String key;
  private final JsonService json;

  public RegisteredBorrowerClient(
    @Value("${lendora.auth-url:http://localhost:8086}") String url,
    @Value("${lendora.provisioning-key:}") String key,
    JsonService json
  ) {
    this.key = key;
    this.json = json;
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(5000);
    this.client = RestClient.builder()
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

  private JsonNode get(String path) {
    if (key.isBlank()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "Registered borrower directory is not configured. Set the shared LENDORA_PROVISIONING_KEY on the lender and account services."
    );
    try {
      var response = client
        .get()
        .uri(path)
        .header("X-Lendora-Provisioning-Key", key)
        .retrieve()
        .body(JsonNode.class);
      if (
        response == null || !response.path("success").asBoolean()
      ) throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Registered borrower directory returned an invalid response"
      );
      return response.path("data");
    } catch (HttpClientErrorException.NotFound e) {
      throw new ResponseStatusException(
        HttpStatus.NOT_FOUND,
        "Registered borrower is no longer available",
        e
      );
    } catch (RestClientException e) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Cannot load registered borrowers. Check lendora-service and the shared provisioning key, then retry.",
        e
      );
    }
  }

  public List<BorrowerEntry> list() {
    var data = get("/internal/lendora/borrowers");
    if (!data.isArray()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "Registered borrower directory returned an invalid response"
    );
    var result = new ArrayList<BorrowerEntry>();
    for (var entry : data) result.add(json.data(entry, BorrowerEntry.class));
    return result;
  }

  public BorrowerEntry find(long id) {
    var borrower = json.data(
      get("/internal/lendora/borrowers/" + id),
      BorrowerEntry.class
    );
    Checks.require(
      borrower.directoryId() == id,
      "Registered borrower identity mismatch"
    );
    return borrower;
  }
}
