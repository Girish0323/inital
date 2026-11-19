package com.hotelsaas.common.error;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BaseException.class)
  public ResponseEntity<ProblemDetail> handle(BaseException ex) {
    HttpStatus status =
        switch (ex.getCode()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
          case CONFLICT -> HttpStatus.CONFLICT;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
          case BUSINESS_RULE_VIOLATION, TENANT_PREFIX_EXHAUSTED -> HttpStatus.UNPROCESSABLE_ENTITY;
          default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    body.setType(URI.create("about:blank"));
    body.setTitle(status.getReasonPhrase());
    body.setProperty("code", ex.getCode().name());
    return ResponseEntity.status(status)
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
