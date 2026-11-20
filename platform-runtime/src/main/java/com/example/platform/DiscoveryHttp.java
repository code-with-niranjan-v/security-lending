package com.example.platform;

import java.io.IOException;
import java.util.Set;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.*;
import org.springframework.http.client.support.HttpRequestWrapper;

/** Resolves internal logical hosts only. External market-data hosts are never balanced or retried. */
public final class DiscoveryHttp {

  private static final Set<String> SERVICES = Set.of(
    "borrower-service",
    "lender-service",
    "lifecycle-service",
    "lendora-service",
    "market-data-service",
    "borrower-fix-adapter",
    "lender-fix-adapter"
  );

  private DiscoveryHttp() {}

  public static ClientHttpResponse execute(
    LoadBalancerClient client,
    HttpRequest request,
    byte[] body,
    ClientHttpRequestExecution execution
  ) throws IOException {
    String service = request.getURI().getHost();
    if (
      service == null || !SERVICES.contains(service)
    ) return execution.execute(request, body);
    if (client == null) throw new IOException(
      "Discovery is unavailable for " + service
    );
    var instance = client.choose(service);
    if (instance == null) throw new IOException(
      "No healthy instance for " + service
    );
    var uri = client.reconstructURI(instance, request.getURI());
    return execution.execute(
      new HttpRequestWrapper(request) {
        @Override
        public java.net.URI getURI() {
          return uri;
        }
      },
      body
    );
  }
}
