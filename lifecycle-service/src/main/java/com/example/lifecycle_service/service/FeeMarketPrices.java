package com.example.lifecycle_service.service;

import com.example.lifecycle_service.model.FeeSecurity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Component
public class FeeMarketPrices {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  private final RestClient market;

  public FeeMarketPrices(
    @Value("${lifecycle.market-data-url:http://localhost:8080}") String url
  ) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    market = RestClient.builder()
      .requestInterceptor((outboundRequest, outboundBody, execution) ->
        com.example.platform.DiscoveryHttp.execute(
          discovery,
          outboundRequest,
          outboundBody,
          execution
        )
      )
      .baseUrl(url)
      .requestFactory(requestFactory)
      .build();
  }

  public NavigableMap<Instant, BigDecimal> history(
    FeeSecurity security,
    Instant cutoff,
    boolean demo
  ) {
    var marks = new TreeMap<Instant, BigDecimal>();
    try {
      var candles = market
        .get()
        .uri(uri ->
          uri
            .path("/api/v1/security-data/history")
            .queryParam("symbol", security.getSymbol())
            .queryParam("exchange", security.getExchange())
            .build()
        )
        .retrieve()
        .body(JsonNode.class);
      if (candles != null && candles.isArray()) {
        for (var candle : candles) {
          if (
            !candle.hasNonNull("close") ||
            !candle.hasNonNull("tradedDateTimestamp")
          ) continue;
          Instant completedAt = Instant.parse(
            candle.get("tradedDateTimestamp").asText()
          ).plusSeconds(60);
          BigDecimal close = candle.get("close").decimalValue();
          if (!completedAt.isAfter(cutoff) && close.signum() > 0) marks.put(
            completedAt,
            close
          );
        }
      }
    } catch (RestClientException exception) {
      if (!demo) {
        throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Market data unavailable",
          exception
        );
      }
    }
    if (
      demo &&
      security.getPrice() != null &&
      security.getPrice().signum() > 0 &&
      security.getPriceAt() != null &&
      !security.getPriceAt().isAfter(cutoff)
    ) {
      marks.put(security.getPriceAt(), security.getPrice());
    }
    return marks;
  }
}
