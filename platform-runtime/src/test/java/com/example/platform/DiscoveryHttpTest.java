package com.example.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;

class DiscoveryHttpTest {

  @Test
  void logicalHostUsesSelectedReplicaAndMutationsAreNeverRetried() throws Exception {
    var balancer = mock(LoadBalancerClient.class);
    var request = mock(HttpRequest.class);
    var execution = mock(ClientHttpRequestExecution.class);
    var logical = URI.create("http://lender-service/api/v1/organizations/3");
    var physical = URI.create("http://localhost:18084/api/v1/organizations/3");
    when(request.getURI()).thenReturn(logical);
    var selected = new DefaultServiceInstance(
      "second",
      "lender-service",
      "localhost",
      18084,
      false
    );
    when(balancer.choose("lender-service")).thenReturn(selected);
    when(balancer.reconstructURI(selected, logical)).thenReturn(physical);
    when(execution.execute(any(), any())).thenThrow(new IOException("crashed"));
    assertThrows(IOException.class, () ->
      DiscoveryHttp.execute(balancer, request, new byte[0], execution)
    );
    verify(execution, times(1)).execute(
      argThat(r -> r.getURI().equals(physical)),
      any()
    );
  }

  @Test
  void externalHostBypassesDiscovery() throws Exception {
    var request = mock(HttpRequest.class);
    var execution = mock(ClientHttpRequestExecution.class);
    when(request.getURI()).thenReturn(
      URI.create("https://query1.finance.yahoo.com/")
    );
    DiscoveryHttp.execute(null, request, new byte[0], execution);
    verify(execution).execute(eq(request), any());
  }
}
