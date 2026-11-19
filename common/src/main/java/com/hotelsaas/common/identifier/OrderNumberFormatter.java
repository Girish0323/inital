package com.hotelsaas.common.identifier;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class OrderNumberFormatter {
  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private final Clock clock;

  public OrderNumberFormatter(Clock clock) {
    this.clock = clock;
  }

  public String format(String prefix, int sequence) {
    return format(prefix, LocalDate.now(clock), sequence);
  }

  public String format(String prefix, LocalDate date, int sequence) {
    TenantPrefixResolver.validate(prefix);
    if (sequence < 1) throw new IllegalArgumentException("Order sequence must be positive");
    return prefix + date.format(DATE) + String.format(sequence <= 9999 ? "%04d" : "%d", sequence);
  }
}
