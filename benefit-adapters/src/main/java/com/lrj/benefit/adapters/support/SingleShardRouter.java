package com.lrj.benefit.adapters.support;

import com.lrj.benefit.application.port.out.ShardRouter;

public final class SingleShardRouter implements ShardRouter {
    @Override public Placement locate(String tenantId, String routingKey) {
        return new Placement("primary", 0);
    }
}
