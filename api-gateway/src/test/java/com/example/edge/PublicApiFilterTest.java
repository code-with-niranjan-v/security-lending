package com.example.edge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class PublicApiFilterTest {

  final PublicApiFilter filter = new PublicApiFilter();

  @Test
  void blocksInternalManagementAndEncodedTraversal() {
    for (String path : new String[] {
      "/accounts/internal/lendora/borrowers",
      "/lender/actuator/env",
      "/actuator/metrics",
      "/lifecycle/api/v1/jobs",
      "/borrower/api/v1/../internal",
      "/accounts/api/v1/%252e%252e",
    }) {
      var exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get(path)
      );
      filter
        .filter(exchange, ignored -> {
          fail("Must not forward " + path);
          return Mono.empty();
        })
        .block();
      assertEquals(404, exchange.getResponse().getStatusCode().value(), path);
    }
  }

  @Test
  void forwardsPublicApiAndCookieButStripsClaimedIdentity() {
    var exchange = MockServerWebExchange.from(
      MockServerHttpRequest.post("/lender/api/v1/organizations/3/loans")
        .header("Cookie", "LENDORA_SESSION=test")
        .header("X-Organization-Id", "4")
        .header("X-Lendora-Provisioning-Key", "forged")
        .header("X-User-Role", "ADMIN")
    );
    filter
      .filter(exchange, forwarded -> {
        var headers = forwarded.getRequest().getHeaders();
        assertEquals("LENDORA_SESSION=test", headers.getFirst("Cookie"));
        assertNull(headers.getFirst("X-Organization-Id"));
        assertNull(headers.getFirst("X-Lendora-Provisioning-Key"));
        assertNull(headers.getFirst("X-User-Role"));
        return Mono.empty();
      })
      .block();
  }
}
