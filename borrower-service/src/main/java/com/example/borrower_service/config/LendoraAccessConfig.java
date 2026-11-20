package com.example.borrower_service.config;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.*;
import tools.jackson.databind.JsonNode;

@Configuration
public class LendoraAccessConfig implements WebMvcConfigurer {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  private final com.example.borrower_service.service.WorkspaceAdminAccess admins;
  private final boolean enabled;
  private final String url;
  private final Set<String> origins;

  public LendoraAccessConfig(
    com.example.borrower_service.service.WorkspaceAdminAccess admins,
    @Value("${lendora.auth-enabled:true}") boolean enabled,
    @Value("${lendora.auth-url:http://localhost:8086}") String url,
    @Value(
      "${lendora.allowed-origins:http://localhost:5176,http://localhost:5175,http://localhost:5174,http://localhost:5173}"
    ) String origins
  ) {
    this.admins = admins;
    this.enabled = enabled;
    this.url = url;
    this.origins = new HashSet<>(Arrays.asList(origins.split(",")));
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
      .addInterceptor(
        new HandlerInterceptor() {
          @Override
          public boolean preHandle(
            HttpServletRequest req,
            HttpServletResponse res,
            Object handler
          ) {
            if (!enabled || "OPTIONS".equals(req.getMethod())) return true;
            res.setHeader("Cache-Control", "no-store");
            String origin = req.getHeader("Origin");
            if (
              !Set.of("GET", "HEAD").contains(req.getMethod()) &&
              origin != null &&
              !origins.contains(origin)
            ) throw new ResponseStatusException(
              HttpStatus.FORBIDDEN,
              "Request origin is not allowed"
            );
            String token =
              req.getCookies() == null
                ? null
                : Arrays.stream(req.getCookies())
                    .filter(c -> c.getName().equals("LENDORA_SESSION"))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
            if (
              token == null || !token.matches("[A-Za-z0-9_-]{43}")
            ) throw new ResponseStatusException(
              HttpStatus.UNAUTHORIZED,
              "Please log in through Lendora"
            );
            JsonNode identity;
            var factory =
              new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(1500);
            factory.setReadTimeout(2500);
            try {
              var response = RestClient.builder()
                .requestInterceptor(
                  (outboundRequest, outboundBody, execution) ->
                    com.example.platform.DiscoveryHttp.execute(
                      discovery,
                      outboundRequest,
                      outboundBody,
                      execution
                    )
                )
                .baseUrl(url)
                .requestFactory(factory)
                .build()
                .get()
                .uri("/api/v1/auth/me")
                .header("Cookie", "LENDORA_SESSION=" + token)
                .retrieve()
                .body(JsonNode.class);
              if (
                response == null || !response.path("success").asBoolean()
              ) throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Please log in again"
              );
              identity = response.path("data");
            } catch (
              org.springframework.web.client.HttpClientErrorException e
            ) {
              throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Your session has expired. Please log in again.",
                e
              );
            } catch (org.springframework.web.client.RestClientException e) {
              throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Account service is unavailable",
                e
              );
            }
            if (
              !"BORROWER".equals(
                identity.path("organization").path("type").asText()
              )
            ) throw new ResponseStatusException(
              HttpStatus.FORBIDDEN,
              "This organization cannot access this workspace"
            );
            long org = identity
              .path("organization")
              .path("platformOrganizationId")
              .asLong();
            if (org <= 0) throw new ResponseStatusException(
              HttpStatus.FORBIDDEN,
              "Workspace is not provisioned"
            );
            var path = req
              .getRequestURI()
              .substring(req.getContextPath().length());
            var variables = req.getAttribute(
              HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE
            );
            if (
              variables instanceof Map<?, ?> values && values.containsKey("org")
            ) {
              try {
                if (
                  Long.parseLong(String.valueOf(values.get("org"))) != org
                ) throw new ResponseStatusException(
                  HttpStatus.FORBIDDEN,
                  "Organization access denied"
                );
              } catch (NumberFormatException e) {
                throw new ResponseStatusException(
                  HttpStatus.FORBIDDEN,
                  "Organization access denied",
                  e
                );
              }
            }
            if (
              path.equals("/api/v1/organizations") &&
              !"GET".equals(req.getMethod())
            ) throw new ResponseStatusException(
              HttpStatus.FORBIDDEN,
              "Create organizations through Lendora"
            );
            req.setAttribute("lendora.organizationId", org);
            req.setAttribute(
              "lendora.userEmail",
              identity.path("user").path("email").asText("")
            );
            if (
              handler instanceof
                org.springframework.web.method.HandlerMethod method &&
              !Set.of("GET", "HEAD").contains(req.getMethod()) &&
              ((method
                .getBeanType()
                .getSimpleName()
                .equals("FundingController") &&
                method.getMethod().getName().equals("approveDeposit")) ||
                (method
                  .getBeanType()
                  .getSimpleName()
                  .equals("OrganizationController") &&
                  Set.of("add", "member", "update").contains(
                    method.getMethod().getName()
                  )))
            ) admins.requireAdmin(req, org);
            return true;
          }
        }
      )
      .addPathPatterns("/api/**");
  }
}
