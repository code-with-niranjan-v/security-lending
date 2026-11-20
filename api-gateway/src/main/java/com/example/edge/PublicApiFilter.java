package com.example.edge;

import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Only public API namespaces cross the edge; internal service calls bypass the gateway. */
@Component
public class PublicApiFilter implements WebFilter, Ordered {

  private static final Set<String> PREFIXES = Set.of(
    "borrower",
    "lender",
    "accounts",
    "market"
  );

  @Override
  public int getOrder() {
    return -100;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (
      path.equals("/actuator/health") || path.startsWith("/actuator/health/")
    ) return chain.filter(exchange);
    String[] parts = path.split("/", -1);
    boolean allowed =
      parts.length >= 5 &&
      PREFIXES.contains(parts[1]) &&
      parts[2].equals("api") &&
      parts[3].equals("v1");
    for (String part : parts) {
      String decoded = org.springframework.web.util.UriUtils.decode(
        part,
        java.nio.charset.StandardCharsets.UTF_8
      );
      if (
        decoded.equals("..") ||
        decoded.contains("/") ||
        decoded.contains("\\") ||
        decoded.contains("%")
      ) allowed = false;
    }
    if (!allowed) {
      exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
      return exchange.getResponse().setComplete();
    }
    // Internal identity is always recomputed by each service from its durable session.
    return chain.filter(
      exchange
        .mutate()
        .request(request ->
          request.headers(headers -> {
            headers.remove("X-Organization-Id");
            headers.remove("X-User-Role");
            headers.remove("X-Lendora-Provisioning-Key");
          })
        )
        .build()
    );
  }
}
