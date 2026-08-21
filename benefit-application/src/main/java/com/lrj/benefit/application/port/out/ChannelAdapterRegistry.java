package com.lrj.benefit.application.port.out;

public interface ChannelAdapterRegistry {
    ChannelAdapter required(String channelCode);
}
