package com.lrj.benefit.domain.service;

import com.lrj.benefit.domain.model.ChannelRoute;

import java.util.Comparator;
import java.util.List;

public final class RoutePolicy {
    public ChannelRoute selectPrimary(List<ChannelRoute> routes) {
        return routes.stream().filter(ChannelRoute::enabled)
                .min(Comparator.comparingInt(ChannelRoute::priority))
                .orElseThrow(() -> new IllegalStateException("no enabled route"));
    }

    public ChannelRoute selectFallback(ChannelRoute primary, List<ChannelRoute> routes,
                                       boolean providerConfirmedNotIssued) {
        if (!providerConfirmedNotIssued) {
            throw new IllegalStateException("fallback is forbidden while provider outcome is unknown");
        }
        if (primary.fallbackRouteId() == null) {
            throw new IllegalStateException("primary route has no fallback");
        }
        return routes.stream()
                .filter(ChannelRoute::enabled)
                .filter(r -> r.routeId().equals(primary.fallbackRouteId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("configured fallback route is unavailable"));
    }
}
