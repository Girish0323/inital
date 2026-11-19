package com.hotelsaas.common.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Correlation-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = request.getHeader(HEADER);
    if (id == null || id.isBlank() || id.length() > 128) id = UUID.randomUUID().toString();
    MDC.put("correlationId", id);
    response.setHeader(HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
