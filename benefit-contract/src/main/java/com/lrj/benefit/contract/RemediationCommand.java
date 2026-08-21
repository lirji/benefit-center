package com.lrj.benefit.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RemediationCommand(
        @NotBlank @Size(max = 128) String externalCommandId,
        @NotNull RemediationAction action,
        @NotBlank @Size(max = 64) String awardItemNo,
        @Size(max = 64) String originalOperationNo,
        @NotBlank @Size(max = 512) String reason,
        @Size(max = 256) String approvalRef) {
}
