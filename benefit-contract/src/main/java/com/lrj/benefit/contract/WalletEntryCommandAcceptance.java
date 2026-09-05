package com.lrj.benefit.contract;

/** 同步落账后的券包命令结果；status/version 均为已提交的目标状态。 */
public record WalletEntryCommandAcceptance(String entryId, String status, long version) {
}
