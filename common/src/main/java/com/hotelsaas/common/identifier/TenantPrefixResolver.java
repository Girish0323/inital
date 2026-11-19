package com.hotelsaas.common.identifier;

import java.text.Normalizer;
import java.util.Locale;

public final class TenantPrefixResolver {
  private TenantPrefixResolver() {}

  public static String fromHotelName(String name) {
    if (name == null) throw new IllegalArgumentException("Hotel name is required");
    String normalized =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toUpperCase(Locale.ROOT);
    String letters = normalized.replaceAll("[^A-Z]", "");
    return (letters + "XXX").substring(0, 3);
  }

  public static String validate(String prefix) {
    if (prefix == null || !prefix.matches("[A-Z]{3}"))
      throw new IllegalArgumentException("Prefix must contain exactly three uppercase letters");
    return prefix;
  }
}
