package com.hotelsaas.gateway;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtValidationFilter implements GlobalFilter, Ordered {
  private static final Set<String> PUBLIC_PREFIXES =
      Set.of(
          "/api/v1/auth/login",
          "/api/v1/auth/refresh",
          "/api/v1/auth/password/forgot",
          "/api/v1/auth/password/reset");
  private final WebClient authClient;

  public JwtValidationFilter(
      WebClient.Builder builder, @Value("${AUTH_URL:http://localhost:8081}") String authUrl) {
    this.authClient = builder.baseUrl(authUrl).build();
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();
    if (path.startsWith("/actuator/")
        || path.equals("/health")
        || PUBLIC_PREFIXES.stream().anyMatch(path::equals)
        || path.startsWith("/api/v1/auth/")) return chain.filter(exchange);
    String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (token == null) {
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      return exchange.getResponse().setComplete();
    }
    return authClient
        .get()
        .uri("/api/v1/auth/validate")
        .header(HttpHeaders.AUTHORIZATION, token)
        .retrieve()
        .toBodilessEntity()
        .map(ignored -> true)
        .onErrorResume(
            error -> {
              exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
              return Mono.just(false);
            })
        .flatMap(valid -> valid ? chain.filter(exchange) : exchange.getResponse().setComplete());
  }

  @Override
  public int getOrder() {
    return -100;
  }
}
