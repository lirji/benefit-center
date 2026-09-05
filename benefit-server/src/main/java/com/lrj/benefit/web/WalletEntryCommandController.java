package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.port.in.WalletEntryCommandUseCase;
import com.lrj.benefit.contract.WalletEntryCommand;
import com.lrj.benefit.contract.WalletEntryCommandAcceptance;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 券包同步状态命令入口，权限沿用现网 benefit.admin。 */
@RestController
@RequestMapping("/openapi/v1/wallet-entries")
public final class WalletEntryCommandController {
    private final WalletEntryCommandUseCase commands;

    public WalletEntryCommandController(WalletEntryCommandUseCase commands) {
        this.commands = commands;
    }

    /** UNUSED → FROZEN；不提供反向 unfreeze。 */
    @PostMapping("/{entryId}:freeze")
    public ResponseEntity<WalletEntryCommandAcceptance> freeze(
            @PathVariable String entryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WalletEntryCommand command) {
        return accepted(entryId, idempotencyKey, WalletEntryCommandUseCase.Action.FREEZE, command);
    }

    /** UNUSED/FROZEN → USED。 */
    @PostMapping("/{entryId}:redeem")
    public ResponseEntity<WalletEntryCommandAcceptance> redeem(
            @PathVariable String entryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WalletEntryCommand command) {
        return accepted(entryId, idempotencyKey, WalletEntryCommandUseCase.Action.REDEEM, command);
    }

    /** USED → REVERSED；与履约 Remediation REVERSE 完全隔离。 */
    @PostMapping("/{entryId}:refund")
    public ResponseEntity<WalletEntryCommandAcceptance> refund(
            @PathVariable String entryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WalletEntryCommand command) {
        return accepted(entryId, idempotencyKey, WalletEntryCommandUseCase.Action.REFUND, command);
    }

    private ResponseEntity<WalletEntryCommandAcceptance> accepted(
            String entryId, String idempotencyKey, WalletEntryCommandUseCase.Action action,
            WalletEntryCommand command) {
        WalletEntryCommandAcceptance result = commands.execute(TenantContext.required(), entryId,
                idempotencyKey, action, command);
        return ResponseEntity.accepted().body(result);
    }
}
