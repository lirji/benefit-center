package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.port.in.CatalogAdminUseCase;
import com.lrj.benefit.application.port.in.IdempotentCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1")
public class AdminCatalogController {
    private final CatalogAdminUseCase admin;
    private final IdempotentCommandExecutor idempotency;
    private final RequestPayloadHasher hasher;

    public AdminCatalogController(CatalogAdminUseCase admin, IdempotentCommandExecutor idempotency,
                                  ObjectMapper json) {
        this.admin = admin;
        this.idempotency = idempotency;
        this.hasher = new RequestPayloadHasher(json);
    }

    @PutMapping("/tenants/{tenantId}")
    public ResponseEntity<Void> saveTenant(@PathVariable String tenantId,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @RequestBody CatalogAdminUseCase.TenantCommand body) {
        requirePath(tenantId, body.tenantId());
        if (!tenantId.equals(TenantContext.required())) {
            throw new IllegalArgumentException("an admin token can provision only its mapped tenant");
        }
        idempotency.execute(tenantId, idempotencyKey, "tenant:" + tenantId, hasher.hash(body),
                () -> admin.saveTenant(body));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/skus/{skuId}")
    public ResponseEntity<Void> saveSku(@PathVariable String skuId,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @RequestBody CatalogAdminUseCase.SkuCommand body) {
        requirePath(skuId, body.skuId());
        String tenantId = TenantContext.required();
        idempotency.execute(tenantId, idempotencyKey, "sku:" + skuId, hasher.hash(body),
                () -> admin.saveSku(tenantId, body));
        return ResponseEntity.noContent().build();
    }

    /** 提交审批只返回 202 Accepted；模板是否 ACTIVE 以后续 workflow 落地结果为准。 */
    @PostMapping("/skus/{skuId}:submit-for-approval")
    public ResponseEntity<CatalogAdminUseCase.SkuSubmitAcceptance> submitSkuForApproval(
            @PathVariable String skuId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Operator", defaultValue = "benefit-center-admin") String assertedOperator,
            @RequestBody CatalogAdminUseCase.SkuSubmitCommand body,
            Authentication authentication) {
        if (body.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        String tenantId = TenantContext.required();
        idempotency.execute(tenantId, idempotencyKey, "sku-submit:" + skuId, hasher.hash(body),
                () -> admin.submitSkuForApproval(tenantId, skuId, body.expectedVersion(),
                        operator(authentication, assertedOperator)));
        return ResponseEntity.accepted().body(new CatalogAdminUseCase.SkuSubmitAcceptance(
                skuId, "PENDING_APPROVAL", Math.addExact(body.expectedVersion(), 1L)));
    }

    @PutMapping("/routes/{routeId}")
    public ResponseEntity<Void> saveRoute(@PathVariable String routeId,
                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                          @RequestBody CatalogAdminUseCase.RouteCommand body) {
        requirePath(routeId, body.routeId());
        String tenantId = TenantContext.required();
        idempotency.execute(tenantId, idempotencyKey, "route:" + routeId, hasher.hash(body),
                () -> admin.saveRoute(tenantId, body));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/inventory/adjustments")
    public ResponseEntity<Void> adjust(@RequestBody CatalogAdminUseCase.InventoryCommand command,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @RequestHeader(value = "X-Operator", defaultValue = "unknown") String assertedOperator,
                                       Authentication authentication) {
        String tenantId = TenantContext.required();
        idempotency.execute(tenantId, idempotencyKey, "inventory-adjustment", hasher.hash(command),
                () -> admin.adjustInventory(tenantId, command, operator(authentication, assertedOperator)));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/code-assets")
    public ResponseEntity<Void> importCode(@RequestBody CatalogAdminUseCase.CodeAssetCommand command,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @RequestHeader(value = "X-Operator", defaultValue = "unknown") String assertedOperator,
                                           Authentication authentication) {
        String tenantId = TenantContext.required();
        idempotency.execute(tenantId, idempotencyKey, "code-asset:" + command.codeAssetId(), hasher.hash(command),
                () -> admin.importCode(tenantId, command, operator(authentication, assertedOperator)));
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
