package com.lrj.benefit.adapters.security;

public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private TenantContext() {}

    public static void set(String tenantId) { CURRENT.set(tenantId); }
    public static String required() {
        String tenant = CURRENT.get();
        if (tenant == null || tenant.isBlank()) throw new IllegalStateException("trusted tenant is unavailable");
        return tenant;
    }
    public static void clear() { CURRENT.remove(); }
}
