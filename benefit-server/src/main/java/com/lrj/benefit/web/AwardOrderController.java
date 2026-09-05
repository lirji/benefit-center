package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.command.AwardIntentCommand;
import com.lrj.benefit.application.port.in.AcceptAwardIntentUseCase;
import com.lrj.benefit.application.port.in.QueryAwardOrderUseCase;
import com.lrj.benefit.application.result.AcceptResult;
import com.lrj.benefit.application.port.out.CellRouter;
import com.lrj.benefit.application.port.out.OperationRepository;
import com.lrj.benefit.contract.AwardIntent;
import com.lrj.benefit.domain.model.AwardItem;
import com.lrj.benefit.domain.model.AwardOrder;
import com.lrj.benefit.domain.model.FulfillmentOperation;
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
    private final OperationRepository operations;

    public AwardOrderController(AcceptAwardIntentUseCase accept, QueryAwardOrderUseCase query,
                                CellRouter cells, OperationRepository operations) {
        this.accept = accept;
        this.query = query;
        this.cells = cells;
        this.operations = operations;
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
        String tenantId = TenantContext.required();
        return query.get(tenantId, orderNo).map(order -> AwardOrderResponse.from(order, operations))
                .orElseThrow(() -> new AwardNotFoundException("award order not found"));
    }

    @GetMapping
    public AwardOrderResponse findBySource(@RequestParam String sourceSystem,
                                           @RequestParam String sourceRequestId) {
        String tenantId = TenantContext.required();
        return query.findBySource(tenantId, sourceSystem, sourceRequestId)
                .map(order -> AwardOrderResponse.from(order, operations))
                .orElseThrow(() -> new AwardNotFoundException("award order not found"));
    }

    public record AwardOrderResponse(String orderNo, String sourceSystem, String sourceRequestId,
                                     String sourceBusinessNo, String recipientRef, String status, String homeCell,
                                     List<AwardItemResponse> items) {
        static AwardOrderResponse from(AwardOrder order, OperationRepository operations) {
            return new AwardOrderResponse(order.orderNo(), order.sourceSystem(), order.sourceRequestId(),
                    order.sourceBusinessNo(), order.recipientRef(), order.status().name(), order.homeCell(),
                    order.items().stream().map(item -> AwardItemResponse.from(order.tenantId(), item, operations)).toList());
        }
    }

    public record AwardItemResponse(String itemNo, String clientItemId, String skuId, String benefitType,
                                    long quantity, Long amountMinor, String currency, String status,
                                    String routeId, String failureCode, String latestOperationNo,
                                    String latestOperationStatus, long skuVersion, String walletEntryId) {
        static AwardItemResponse from(String tenantId, AwardItem item, OperationRepository operations) {
            FulfillmentOperation latest = operations.findByItem(tenantId, item.itemNo()).stream().findFirst().orElse(null);
            return new AwardItemResponse(item.itemNo(), item.clientItemId(), item.skuId(),
                    item.benefitType().name(), item.quantity(), item.amountMinor(), item.currency(),
                    item.status().name(), item.routeId(), item.failureCode(),
                    latest == null ? null : latest.operationNo(),
                    latest == null ? null : latest.status().name(), item.skuVersion(), item.walletEntryId());
        }
    }
}
