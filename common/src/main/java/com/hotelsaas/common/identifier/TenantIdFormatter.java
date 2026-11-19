package com.hotelsaas.common.identifier;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class TenantIdFormatter {
  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private final Clock clock;

  public TenantIdFormatter(Clock clock) {
    this.clock = clock;
  }

  public String format(String prefix, int sequence) {
    return format(prefix, LocalDate.now(clock), sequence);
  }

  public String format(String prefix, LocalDate date, int sequence) {
    TenantPrefixResolver.validate(prefix);
    if (sequence < 1 || sequence > 999)
      throw new IllegalArgumentException("Tenant sequence must be between 1 and 999");
    return prefix + date.format(DATE) + String.format("%03d", sequence);
  }
}
