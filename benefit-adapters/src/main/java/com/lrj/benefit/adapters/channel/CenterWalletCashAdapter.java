package com.lrj.benefit.adapters.channel;

import com.lrj.benefit.application.port.out.ChannelAdapter;
import com.lrj.benefit.domain.model.AdapterCapabilities;

/** 中心钱包现金通道。实际入账由履约收敛事务完成，通道本身没有外部调用或写入。 */
public final class CenterWalletCashAdapter implements ChannelAdapter {
    public static final String CHANNEL_CODE = "CENTER_WALLET_CASH";
    private static final AdapterCapabilities CAPABILITIES = new AdapterCapabilities(true, true, false, false);
    private static final String INVALID_ITEM = "WALLET_CASH_INVALID_ITEM";
    private static final String REVERSE_NOT_SUPPORTED = "WALLET_CASH_REVERSE_NOT_SUPPORTED";

    @Override public String channelCode() { return CHANNEL_CODE; }
    @Override public AdapterCapabilities capabilities() { return CAPABILITIES; }

    /** 只接受单个带币种的正金额项，由现有工作事务同时入账并更新发放结果。 */
    @Override public ChannelResult issue(ChannelCommand command) {
        if (command.quantity() != 1 || command.amountMinor() == null || command.amountMinor() <= 0
                || command.currency() == null || command.currency().length() != 3) {
            return new ChannelResult(ChannelResult.ResultType.FINAL_FAILURE, null, INVALID_ITEM, null);
        }
        return new ChannelResult(ChannelResult.ResultType.SUCCEEDED, command.operationNo(), null, null);
    }

    /** 入账与操作成功同事务提交。租约恢复可以重新校验同一操作，再由收敛事务入账。 */
    @Override public ChannelResult query(ChannelCommand command) {
        return issue(command);
    }

    /** 渠道冲正需要独立的余额可用性策略，以免绕过钱包命令的业务限制。 */
    @Override public ChannelResult reverse(ChannelCommand command) {
        return new ChannelResult(ChannelResult.ResultType.FINAL_FAILURE, null, REVERSE_NOT_SUPPORTED, null);
    }
}
