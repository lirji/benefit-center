package com.lrj.benefit.domain.model;

import java.util.Objects;

public record ChannelRoute(
        String routeId,
        String skuId,
        int priority,
        String channelCode,
        InventoryOwnerType ownerType,
        String fallbackRouteId,
        InventoryReserveMode reserveMode,
        boolean enabled,
        AdapterCapabilities capabilities) {

    public ChannelRoute {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(channelCode, "channelCode");
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(reserveMode, "reserveMode");
        Objects.requireNonNull(capabilities, "capabilities");
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
    }
}
