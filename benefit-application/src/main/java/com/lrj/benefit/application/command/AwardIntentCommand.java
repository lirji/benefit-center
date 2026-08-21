package com.lrj.benefit.application.command;

import com.lrj.benefit.contract.AwardIntent;

public record AwardIntentCommand(
        String tenantId,
        String idempotencyKey,
        String requestHash,
        String homeCell,
        AwardIntent intent) {
}
