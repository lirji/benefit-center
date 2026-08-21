package com.lrj.benefit.application.port.out;

import java.util.function.Supplier;

public interface UnitOfWork {
    <T> T required(Supplier<T> work);

    default void required(Runnable work) {
        required(() -> { work.run(); return null; });
    }
}
