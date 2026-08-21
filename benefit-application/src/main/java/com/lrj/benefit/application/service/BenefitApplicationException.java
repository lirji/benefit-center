package com.lrj.benefit.application.service;

import com.lrj.benefit.contract.BenefitErrorCode;

public final class BenefitApplicationException extends RuntimeException {
    private final BenefitErrorCode code;

    public BenefitApplicationException(BenefitErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public BenefitErrorCode code() { return code; }
}
