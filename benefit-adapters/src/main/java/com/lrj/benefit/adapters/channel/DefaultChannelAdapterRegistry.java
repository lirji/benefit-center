package com.lrj.benefit.adapters.channel;

import com.lrj.benefit.application.port.out.ChannelAdapter;
import com.lrj.benefit.application.port.out.ChannelAdapterRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DefaultChannelAdapterRegistry implements ChannelAdapterRegistry {
    private final Map<String, ChannelAdapter> adapters;

    public DefaultChannelAdapterRegistry(List<ChannelAdapter> values) {
        Map<String, ChannelAdapter> byCode = new HashMap<>();
        for (ChannelAdapter adapter : values) {
            if (byCode.putIfAbsent(adapter.channelCode(), adapter) != null) {
                throw new IllegalStateException("duplicate channel adapter: " + adapter.channelCode());
            }
        }
        this.adapters = Map.copyOf(byCode);
    }

    @Override public ChannelAdapter required(String channelCode) {
        ChannelAdapter adapter = adapters.get(channelCode);
        if (adapter == null) throw new IllegalStateException("channel adapter is not installed: " + channelCode);
        return adapter;
    }
}
