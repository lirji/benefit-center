package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.command.AwardIntentCommand;
import com.lrj.benefit.application.port.in.AcceptAwardIntentUseCase;
import com.lrj.benefit.application.port.in.QueryAwardOrderUseCase;
import com.lrj.benefit.application.result.AcceptResult;
import com.lrj.benefit.application.port.out.CellRouter;
import com.lrj.benefit.contract.AwardIntent;
import com.lrj.benefit.domain.model.AwardItem;
import com.lrj.benefit.domain.model.AwardOrder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/openapi/v1/award-orders")
public class AwardOrderController {
    private final AcceptAwardIntentUseCase accept;
    private final QueryAwardOrderUseCase query;
    private final CellRouter cells;

    public AwardOrderController(AcceptAwardIntentUseCase accept, QueryAwardOrderUseCase query,
                                CellRouter cells) {
        this.accept = accept;
        this.query = query;
        this.cells = cells;
    }

    @PostMapping
    public ResponseEntity<AcceptResult> accept(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody AwardIntent intent) {
        String tenantId = TenantContext.required();
        String homeCell = cells.homeCell(tenantId);
        if (!cells.isLocal(homeCell)) throw new IllegalStateException("tenant is routed to another cell: " + homeCell);
        AcceptResult result = accept.accept(new AwardIntentCommand(tenantId, idempotencyKey,
                null, homeCell, intent));
        return ResponseEntity.accepted().location(URI.create("/openapi/v1/award-orders/" + result.awardOrderNo()))
                .body(result);
    }

    @GetMapping("/{orderNo}")
    public AwardOrderResponse get(@PathVariable String orderNo) {
        return query.get(TenantContext.required(), orderNo).map(AwardOrderResponse::from)
                .orElseThrow(() -> new AwardNotFoundException("award order not found"));
    }

    @GetMapping
    public AwardOrderResponse findBySource(@RequestParam String sourceSystem,
                                           @RequestParam String sourceRequestId) {
        return query.findBySource(TenantContext.required(), sourceSystem, sourceRequestId)
                .map(AwardOrderResponse::from)
                .orElseThrow(() -> new AwardNotFoundException("award order not found"));
    }

    public record AwardOrderResponse(String orderNo, String sourceSystem, String sourceRequestId,
                                     String sourceBusinessNo, String status, String homeCell,
                                     List<AwardItemResponse> items) {
        static AwardOrderResponse from(AwardOrder order) {
            return new AwardOrderResponse(order.orderNo(), order.sourceSystem(), order.sourceRequestId(),
                    order.sourceBusinessNo(), order.status().name(), order.homeCell(),
                    order.items().stream().map(AwardItemResponse::from).toList());
        }
    }

    public record AwardItemResponse(String itemNo, String clientItemId, String skuId, String benefitType,
                                    long quantity, Long amountMinor, String currency, String status,
                                    String routeId, String failureCode) {
        static AwardItemResponse from(AwardItem item) {
            return new AwardItemResponse(item.itemNo(), item.clientItemId(), item.skuId(),
                    item.benefitType().name(), item.quantity(), item.amountMinor(), item.currency(),
                    item.status().name(), item.routeId(), item.failureCode());
        }
    }
}
