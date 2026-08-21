package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.port.in.CatalogAdminUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1")
public class AdminCatalogController {
    private final CatalogAdminUseCase admin;
    public AdminCatalogController(CatalogAdminUseCase admin) { this.admin = admin; }

    @PutMapping("/tenants/{tenantId}")
    public ResponseEntity<Void> saveTenant(@PathVariable String tenantId,
                                           @RequestBody CatalogAdminUseCase.TenantCommand body) {
        requirePath(tenantId, body.tenantId());
        if (!tenantId.equals(TenantContext.required())) {
            throw new IllegalArgumentException("an admin token can provision only its mapped tenant");
        }
        admin.saveTenant(body);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/skus/{skuId}")
    public ResponseEntity<Void> saveSku(@PathVariable String skuId,
                                        @RequestBody CatalogAdminUseCase.SkuCommand body) {
        requirePath(skuId, body.skuId());
        admin.saveSku(TenantContext.required(), body);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/routes/{routeId}")
    public ResponseEntity<Void> saveRoute(@PathVariable String routeId,
                                          @RequestBody CatalogAdminUseCase.RouteCommand body) {
        requirePath(routeId, body.routeId());
        admin.saveRoute(TenantContext.required(), body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/inventory/adjustments")
    public ResponseEntity<Void> adjust(@RequestBody CatalogAdminUseCase.InventoryCommand command,
                                       @RequestHeader(value = "X-Operator", defaultValue = "unknown") String assertedOperator,
                                       Authentication authentication) {
        admin.adjustInventory(TenantContext.required(), command, operator(authentication, assertedOperator));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/code-assets")
    public ResponseEntity<Void> importCode(@RequestBody CatalogAdminUseCase.CodeAssetCommand command,
                                           @RequestHeader(value = "X-Operator", defaultValue = "unknown") String assertedOperator,
                                           Authentication authentication) {
        admin.importCode(TenantContext.required(), command, operator(authentication, assertedOperator));
        return ResponseEntity.accepted().build();
    }

    private static void requirePath(String path, String body) {
        if (!path.equals(body)) throw new IllegalArgumentException("path id does not match body id");
    }

    private static String operator(Authentication authentication, String assertedOperator) {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return authentication.getName();
        }
        return assertedOperator;
    }
}
