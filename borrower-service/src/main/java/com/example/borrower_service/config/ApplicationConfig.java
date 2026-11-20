package com.example.borrower_service.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@EnableScheduling
public class ApplicationConfig implements WebMvcConfigurer {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  @Value(
    "${lendora.allowed-origins:http://localhost:5176,http://localhost:5175,http://localhost:5174,http://localhost:5173}"
  )
  private String lendoraOrigins;

  private final String frontend;

  public ApplicationConfig(
    @Value("${borrower.frontend-origin:http://localhost:5173}") String frontend
  ) {
    this.frontend = frontend;
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public RestClient marketDataClient(
    @Value("${borrower.market-data-url:http://localhost:8080}") String url
  ) {
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    return RestClient.builder()
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

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
      .addMapping("/api/**")
      .allowedOrigins(lendoraOrigins.split(","))
      .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
      .allowedHeaders("*")
      .allowCredentials(true);
  }
}
