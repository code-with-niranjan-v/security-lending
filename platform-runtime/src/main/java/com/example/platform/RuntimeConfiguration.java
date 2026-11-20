package com.example.platform;

import jakarta.servlet.Filter;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy
public class RuntimeConfiguration {

  @Bean
  InfoContributor instanceInfo(
    @Value("${spring.application.name}") String name,
    @Value("${server.port:8080}") String port
  ) {
    return builder ->
      builder.withDetail("instance", Map.of("id", name + ":" + port));
  }

  @Bean
  Filter instanceHeader(
    @Value("${spring.application.name}") String name,
    @Value("${server.port:8080}") String port
  ) {
    return (request, response, chain) -> {
      ((jakarta.servlet.http.HttpServletResponse) response).setHeader(
        "X-Service-Instance",
        name + ":" + port
      );
      chain.doFilter(request, response);
    };
  }

  @Bean
  @ConditionalOnProperty(
    name = "spring.cloud.discovery.enabled",
    havingValue = "true"
  )
  org.springframework.web.client.RestTemplate platformHealthRestTemplate() {
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(2000);
    return new org.springframework.web.client.RestTemplate(factory);
  }

  @Bean
  @ConditionalOnProperty(name = "eureka.client.enabled", havingValue = "true")
  com.netflix.appinfo.HealthCheckHandler readinessRegistration(
    org.springframework.boot.health.actuate.endpoint.HealthEndpoint health
  ) {
    return ignored -> {
      var readiness = health.healthForPath("readiness");
      return readiness != null &&
        org.springframework.boot.health.contributor.Status.UP.equals(
          readiness.getStatus()
        )
        ? com.netflix.appinfo.InstanceInfo.InstanceStatus.UP
        : com.netflix.appinfo.InstanceInfo.InstanceStatus.DOWN;
    };
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
    name = "platform.coordination.enabled",
    havingValue = "true"
  )
  public WorkCoordinator workCoordinator(
    DataSource source,
    PlatformTransactionManager transactions,
    @Value("${spring.application.name}") String name,
    @Value("${server.port:8080}") String port
  ) {
    return new WorkCoordinator(
      new JdbcTemplate(source),
      transactions,
      name + ":" + port
    );
  }

  @Bean
  @ConditionalOnProperty(
    name = "platform.coordination.enabled",
    havingValue = "true"
  )
  ScheduledLeaseAspect scheduledLeaseAspect(WorkCoordinator coordinator) {
    return new ScheduledLeaseAspect(coordinator);
  }
}
