package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.UnitOfWork;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

public final class SpringUnitOfWork implements UnitOfWork {
    private final TransactionTemplate transactions;
    public SpringUnitOfWork(TransactionTemplate transactions) { this.transactions = transactions; }

    @Override public <T> T required(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
