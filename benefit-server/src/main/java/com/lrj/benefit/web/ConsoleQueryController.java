package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.AttentionOrderView;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.CodeAssetView;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.Identity;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.InventoryView;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.Overview;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.RemediationView;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.RouteView;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.SkuView;
import com.lrj.benefit.application.port.in.ConsoleQueryUseCase.TenantView;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 运营台读接口。secure 模式沿用 /admin/v1 的 benefit.admin；dev 模式返回全权限沙箱身份。 */
@RestController
@RequestMapping("/admin/v1")
public class ConsoleQueryController {
    private static final List<String> DEV_SCOPES = List.of(
            "benefit.admin", "benefit.award.read", "benefit.award.write", "benefit.remediate");

    private final ConsoleQueryUseCase queries;

    public ConsoleQueryController(ConsoleQueryUseCase queries) {
        this.queries = queries;
    }

    @GetMapping("/console/me")
    public Identity me(Authentication authentication) {
        String tenantId = tenant();
        String subject = subject(authentication);
        List<String> scopes = scopes(authentication);
        return queries.me(tenantId, subject, scopes.isEmpty() ? DEV_SCOPES : scopes);
    }

    @GetMapping("/console/overview")
    public Overview overview() {
        return queries.overview(tenant());
    }

    @GetMapping("/tenants/current")
    public TenantView currentTenant() {
        return queries.currentTenant(tenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant config not found"));
    }

    @GetMapping("/skus")
    public List<SkuView> skus(@RequestParam(required = false) String status,
                              @RequestParam(required = false) String afterSkuId,
                              @RequestParam(defaultValue = "20") int limit) {
        return queries.listSkus(tenant(), status, afterSkuId, limit);
    }

    @GetMapping("/routes")
    public List<RouteView> routes(@RequestParam(required = false) String skuId,
                                  @RequestParam(defaultValue = "20") int limit) {
        return queries.listRoutes(tenant(), skuId, limit);
    }

    @GetMapping("/inventory/accounts")
    public List<InventoryView> inventory(@RequestParam(defaultValue = "20") int limit) {
        return queries.listInventory(tenant(), limit);
    }

    @GetMapping("/console/attention-orders")
    public List<AttentionOrderView> attention(@RequestParam(defaultValue = "20") int limit) {
        return queries.attentionOrders(tenant(), limit);
    }

    @GetMapping("/remediations")
    public List<RemediationView> remediations(@RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "20") int limit) {
        return queries.listRemediations(tenant(), status, limit);
    }

    @GetMapping("/code-assets")
    public List<CodeAssetView> codes(@RequestParam(required = false) String skuId,
                                     @RequestParam(defaultValue = "20") int limit) {
        return queries.listCodeAssets(tenant(), skuId, limit);
    }

    private static String tenant() {
        try {
            return TenantContext.required();
        } catch (IllegalStateException missing) {
            throw new IllegalArgumentException("tenant context is required");
        }
    }

    private static String subject(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return "dev";
        }
        return authentication.getName();
    }

    private static List<String> scopes(Authentication authentication) {
        if (authentication == null) return List.of();
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(value -> value.startsWith("SCOPE_") ? value.substring(6) : value)
                .filter(value -> value.startsWith("benefit."))
                .distinct()
                .toList();
    }
}
