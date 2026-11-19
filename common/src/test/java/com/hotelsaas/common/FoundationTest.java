package com.hotelsaas.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hotelsaas.common.error.*;
import com.hotelsaas.common.identifier.*;
import com.hotelsaas.common.persistence.BaseEntity;
import com.hotelsaas.common.tenant.TenantContext;
import com.hotelsaas.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.*;

class FoundationTest {
  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  @Test
  void tenantContextAndPropagation() {
    TenantContext.setTenantId("ABC20261117001");
    assertEquals("ABC20261117001", TenantContext.requireTenantId());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      assertEquals(
          "ABC20261117001",
          pool.submit(TenantContext.propagate((Callable<String>) TenantContext::requireTenantId))
              .get());
      FutureTask<String> task = new FutureTask<>(TenantContext::requireTenantId);
      TenantContext.propagating(pool).execute(task);
      assertEquals("ABC20261117001", task.get());
    } catch (Exception e) {
      fail(e);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void requireTenantFailsWhenUnset() {
    assertThrows(IllegalStateException.class, TenantContext::requireTenantId);
  }

  @Test
  void prefixStripsAccentsSpacesAndNonLettersAndPads() {
    assertEquals("CAF", TenantPrefixResolver.fromHotelName(" Caf\u00e9 123 Bistro!"));
    assertEquals("ABC", TenantPrefixResolver.fromHotelName("1 A B C Bistro!"));
    assertEquals("AXX", TenantPrefixResolver.fromHotelName("A 1"));
  }

  @Test
  void tenantIdentifierUsesInjectedClockAndPaddedCounter() {
    Clock c = Clock.fixed(Instant.parse("2026-11-16T18:30:00Z"), ZoneId.of("Asia/Kolkata"));
    assertEquals("ABC20261117001", new TenantIdFormatter(c).format("ABC", 1));
    assertEquals(
        "ABC20261118009", new TenantIdFormatter(c).format("ABC", LocalDate.of(2026, 11, 18), 9));
  }

  @Test
  void orderIdentifierWidensAfter9999() {
    Clock c = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);
    var f = new OrderNumberFormatter(c);
    assertEquals("ABC202611180001", f.format("ABC", 1));
    assertEquals("ABC2026111810000", f.format("ABC", 10000));
  }

  @Test
  void typedErrorMapsToProblemDetail() {
    var response = new GlobalExceptionHandler().handle(new NotFoundException("Hotel missing"));
    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals("application/problem+json", response.getHeaders().getContentType().toString());
    assertEquals("NOT_FOUND", response.getBody().getProperties().get("code"));
  }

  @Test
  void correlationFilterAddsAndReturnsId() throws Exception {
    var filter = new CorrelationIdFilter();
    var req = new MockHttpServletRequest();
    var res = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    doAnswer(
            invocation -> {
              assertEquals(
                  res.getHeader(CorrelationIdFilter.HEADER), org.slf4j.MDC.get("correlationId"));
              return null;
            })
        .when(chain)
        .doFilter(req, res);
    filter.doFilter(req, res, chain);
    assertNotNull(res.getHeader(CorrelationIdFilter.HEADER));
    verify(chain).doFilter(req, res);
  }

  @Test
  void entityHasStringTenantAndAuditingMetadata() throws Exception {
    var entity = new SampleEntity();
    entity.setTenantId("ABC20261117001");
    assertEquals("ABC20261117001", entity.getTenantId());
    assertNotNull(entity.getId());
    assertEquals("ACTIVE", entity.getStatus());
    assertTrue(
        BaseEntity.class
            .getDeclaredField("createdDate")
            .isAnnotationPresent(org.springframework.data.annotation.CreatedDate.class));
    assertTrue(
        BaseEntity.class
            .getDeclaredField("updatedDate")
            .isAnnotationPresent(org.springframework.data.annotation.LastModifiedDate.class));
  }

  static class SampleEntity extends BaseEntity {}
}
