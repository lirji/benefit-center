package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.SkuView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面向内部服务的只读权益目录。
 * 与运营后台分离，避免为营销绑定 SKU 授予 benefit.admin 管理权限。
 */
@RestController
@RequestMapping("/internal/v1/catalog")
public class InternalCatalogController {
    private final ConsoleQueryUseCase queries;

    public InternalCatalogController(ConsoleQueryUseCase queries) {
        this.queries = queries;
    }

    /** 按已经过认证边界确认的业务租户读取 SKU，绝不从登录组织推导租户。 */
    @GetMapping("/skus")
    public List<SkuView> skus(@RequestParam(required = false) String status,
                              @RequestParam(required = false) String afterSkuId,
                              @RequestParam(defaultValue = "20") int limit) {
        return queries.listSkus(TenantContext.required(), status, afterSkuId, limit);
    }
}
