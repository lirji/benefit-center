package com.lrj.benefit.domain.service;

import java.util.Collection;

public final class LedgerInvariant {
    public long netQuantity(Collection<Long> signedQuantities) {
        long result = 0;
        for (Long quantity : signedQuantities) {
            result = Math.addExact(result, quantity);
        }
        return result;
    }

    public long netAmount(Collection<Long> signedAmounts) {
        long result = 0;
        for (Long amount : signedAmounts) {
            result = Math.addExact(result, amount);
        }
        return result;
    }
}
