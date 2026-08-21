package com.lrj.benefit.domain.model;

public record AdapterCapabilities(
        boolean supportsIdempotency,
        boolean supportsQuery,
        boolean supportsReverse,
        boolean supportsReserveConfirmCancel) {
}
