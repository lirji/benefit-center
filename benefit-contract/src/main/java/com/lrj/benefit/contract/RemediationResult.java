package com.lrj.benefit.contract;

public record RemediationResult(
        String externalCommandId,
        String remediationNo,
        String status,
        String reference,
        String errorCode) {
}
