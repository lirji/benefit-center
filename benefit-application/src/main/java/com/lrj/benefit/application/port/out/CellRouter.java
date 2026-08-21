package com.lrj.benefit.application.port.out;

public interface CellRouter {
    String homeCell(String tenantId);
    boolean isLocal(String cellId);
}
