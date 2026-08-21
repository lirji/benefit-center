package com.lrj.benefit.adapters.channel;

import com.lrj.benefit.application.port.out.ChannelAdapter;
import com.lrj.benefit.application.port.out.CodeAssetRepository;
import com.lrj.benefit.domain.model.AdapterCapabilities;

public final class CenterCodeAdapter implements ChannelAdapter {
    public static final String CHANNEL_CODE = "CENTER_CODE";
    private final CodeAssetRepository codes;
    public CenterCodeAdapter(CodeAssetRepository codes) { this.codes = codes; }

    @Override public String channelCode() { return CHANNEL_CODE; }
    @Override public AdapterCapabilities capabilities() { return new AdapterCapabilities(true, true, true, true); }

    @Override public ChannelResult issue(ChannelCommand command) {
        return codes.issueOne(command.tenantId(), command.skuId(), command.itemNo(), command.operationNo())
                .map(code -> new ChannelResult(ChannelResult.ResultType.SUCCEEDED,
                        code.deliveryReference(), null, null))
                .orElseGet(() -> new ChannelResult(ChannelResult.ResultType.NOT_ISSUED,
                        null, "CODE_POOL_EXHAUSTED", "no available redemption code"));
    }

    @Override public ChannelResult query(ChannelCommand command) {
        return codes.findIssued(command.tenantId(), command.itemNo())
                .map(code -> new ChannelResult(ChannelResult.ResultType.SUCCEEDED,
                        code.deliveryReference(), null, null))
                .orElseGet(() -> new ChannelResult(ChannelResult.ResultType.NOT_ISSUED,
                        null, "CODE_NOT_ISSUED", null));
    }

    @Override public ChannelResult reverse(ChannelCommand command) {
        return codes.reverse(command.tenantId(), command.itemNo(), command.operationNo())
                ? new ChannelResult(ChannelResult.ResultType.SUCCEEDED, null, null, null)
                : new ChannelResult(ChannelResult.ResultType.FINAL_FAILURE, null,
                "CODE_ALREADY_USED_OR_NOT_FOUND", null);
    }
}
