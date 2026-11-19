package com.hotelsaas.common.tenant;

public final class TenantContext {
  private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

  private TenantContext() {}

  public static void setTenantId(String tenantId) {
    TENANT.set(tenantId);
  }

  public static String getTenantId() {
    return TENANT.get();
  }

  public static String requireTenantId() {
    String id = getTenantId();
    if (id == null || id.isBlank()) throw new IllegalStateException("Tenant ID is not set");
    return id;
  }

  public static void clear() {
    TENANT.remove();
  }

  public static Runnable propagate(Runnable task) {
    String captured = getTenantId();
    return () -> {
      try {
        if (captured == null) clear();
        else setTenantId(captured);
        task.run();
      } finally {
        clear();
      }
    };
  }

  public static <T> java.util.concurrent.Callable<T> propagate(
      java.util.concurrent.Callable<T> task) {
    String captured = getTenantId();
    return () -> {
      try {
        if (captured == null) clear();
        else setTenantId(captured);
        return task.call();
      } finally {
        clear();
      }
    };
  }

  public static java.util.concurrent.Executor propagating(java.util.concurrent.Executor delegate) {
    return task -> delegate.execute(propagate(task));
  }
}
