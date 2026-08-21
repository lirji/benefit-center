package com.lrj.benefit.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record DecisionReference(
        @Size(max = 128) String decisionId,
        @Size(max = 64) String activityId,
        @Min(1) Integer activityVersion) {
}
