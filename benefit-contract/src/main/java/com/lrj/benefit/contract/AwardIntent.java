package com.lrj.benefit.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record AwardIntent(
        @NotBlank @Pattern(regexp = "1\\.0") String schemaVersion,
        @NotBlank @Size(max = 64) String sourceSystem,
        @NotBlank @Size(max = 128) String sourceRequestId,
        @Size(max = 128) String sourceBusinessNo,
        @NotBlank @Size(max = 256) String recipientRef,
        @Valid DecisionReference decision,
        @NotNull PartialPolicy partialPolicy,
        @NotEmpty @Size(max = 20) List<@Valid AwardItemIntent> items,
        @Size(max = 32) Map<@Size(max = 64) String, @Size(max = 256) String> trace) {

    public AwardIntent {
        items = items == null ? List.of() : List.copyOf(items);
        trace = trace == null ? Map.of() : Map.copyOf(trace);
    }
}
