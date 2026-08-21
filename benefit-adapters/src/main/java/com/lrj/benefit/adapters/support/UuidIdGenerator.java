package com.lrj.benefit.adapters.support;

import com.lrj.benefit.application.port.out.IdGenerator;

import java.util.UUID;

public final class UuidIdGenerator implements IdGenerator {
    @Override public String next(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
