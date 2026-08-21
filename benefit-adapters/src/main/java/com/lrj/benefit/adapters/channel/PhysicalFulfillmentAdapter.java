package com.lrj.benefit.adapters.channel;

import com.lrj.benefit.application.port.out.ChannelAdapter;
import com.lrj.benefit.application.port.out.PhysicalFulfillmentRepository;
import com.lrj.benefit.domain.model.AdapterCapabilities;

public final class PhysicalFulfillmentAdapter implements ChannelAdapter {
    public static final String CHANNEL_CODE = "CENTER_PHYSICAL";
    private final PhysicalFulfillmentRepository physical;
    public PhysicalFulfillmentAdapter(PhysicalFulfillmentRepository physical) { this.physical = physical; }

    @Override public String channelCode() { return CHANNEL_CODE; }
    @Override public AdapterCapabilities capabilities() { return new AdapterCapabilities(true, true, true, false); }

    @Override public ChannelResult issue(ChannelCommand command) {
        var result = physical.issue(command.tenantId(), command.itemNo(), command.recipientRef(), command.operationNo());
        return new ChannelResult(ChannelResult.ResultType.SUCCEEDED, result.fulfillmentReference(), null, null);
    }

    @Override public ChannelResult query(ChannelCommand command) {
        return physical.find(command.tenantId(), command.itemNo())
                .map(result -> new ChannelResult(ChannelResult.ResultType.SUCCEEDED,
                        result.fulfillmentReference(), null, result.status()))
                .orElseGet(() -> new ChannelResult(ChannelResult.ResultType.NOT_ISSUED, null,
                        "FULFILLMENT_NOT_FOUND", null));
    }

    @Override public ChannelResult reverse(ChannelCommand command) {
        return physical.reverseBeforeShipment(command.tenantId(), command.itemNo(), command.operationNo())
                ? new ChannelResult(ChannelResult.ResultType.SUCCEEDED, null, null, null)
                : new ChannelResult(ChannelResult.ResultType.FINAL_FAILURE, null,
                "ALREADY_SHIPPED_OR_CANCELLED", null);
    }
}
