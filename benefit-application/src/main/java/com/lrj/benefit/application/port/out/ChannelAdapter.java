package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.AdapterCapabilities;

public interface ChannelAdapter {
    String channelCode();
    AdapterCapabilities capabilities();
    ChannelResult issue(ChannelCommand command);
    ChannelResult query(ChannelCommand command);
    ChannelResult reverse(ChannelCommand command);

    record ChannelCommand(String tenantId, String operationNo, String itemNo, String skuId,
                          String recipientRef, long quantity, Long amountMinor, String currency) {}

    record ChannelResult(ResultType type, String providerReference, String errorCode, String message) {
        public enum ResultType { SUCCEEDED, RETRYABLE_FAILURE, FINAL_FAILURE, UNKNOWN, NOT_ISSUED }
    }
}
