package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.port.in.WalletQueryUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Slice 1 客服券包查询，沿用 benefit.admin 权限。 */
@RestController
@RequestMapping("/admin/v1/wallets")
public class WalletController {
    private final WalletQueryUseCase wallets;

    public WalletController(WalletQueryUseCase wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/{subjectRef}")
    public WalletQueryUseCase.WalletView wallet(@PathVariable String subjectRef) {
        return wallets.wallet(TenantContext.required(), subjectRef);
    }

    @GetMapping("/{subjectRef}/entries")
    public List<WalletQueryUseCase.WalletEntryView> entries(
            @PathVariable String subjectRef,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String skuId,
            @RequestParam(required = false) String afterEntryId,
            @RequestParam(defaultValue = "20") int limit) {
        return wallets.entries(TenantContext.required(), subjectRef, status, skuId, afterEntryId, limit);
    }
}
