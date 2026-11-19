package com.hotelsaas.common.config;

import com.hotelsaas.common.error.GlobalExceptionHandler;
import com.hotelsaas.common.web.CorrelationIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
public class CommonAutoConfiguration {
  @Bean
  GlobalExceptionHandler globalExceptionHandler() {
    return new GlobalExceptionHandler();
  }

  @Bean
  CorrelationIdFilter correlationIdFilter() {
    return new CorrelationIdFilter();
  }
}
