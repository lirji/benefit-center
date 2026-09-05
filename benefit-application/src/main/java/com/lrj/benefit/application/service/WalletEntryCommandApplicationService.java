package com.lrj.benefit.application.service;

import com.lrj.benefit.application.port.in.IdempotentCommandExecutor;
import com.lrj.benefit.application.port.in.WalletEntryCommandUseCase;
import com.lrj.benefit.application.port.out.IdGenerator;
import com.lrj.benefit.application.port.out.OutboxRepository;
import com.lrj.benefit.application.port.out.WalletRepository;
import com.lrj.benefit.contract.BenefitErrorCode;
import com.lrj.benefit.contract.MessageEnvelope;
import com.lrj.benefit.contract.WalletEntryCommand;
import com.lrj.benefit.contract.WalletEntryCommandAcceptance;
import com.lrj.benefit.contract.WalletFulfillmentEvent;
import com.lrj.benefit.domain.model.WalletAssetType;
import com.lrj.benefit.domain.model.WalletEntry;
import com.lrj.benefit.domain.model.WalletEntryStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 券包状态命令服务。状态 CAS、现金流水、首次幂等结果与事实 outbox 共享本地事务。
 */
public final class WalletEntryCommandApplicationService implements WalletEntryCommandUseCase {
    private final WalletRepository wallets;
    private final IdempotentCommandExecutor idempotency;
    private final OutboxRepository outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public WalletEntryCommandApplicationService(WalletRepository wallets,
            IdempotentCommandExecutor idempotency, OutboxRepository outbox,
            IdGenerator ids, Clock clock) {
        this.wallets = Objects.requireNonNull(wallets);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.outbox = Objects.requireNonNull(outbox);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public WalletEntryCommandAcceptance execute(String tenantId, String entryId, String idempotencyKey,
                                                 Action action, WalletEntryCommand command) {
        require("tenantId", tenantId, 64);
        require("entryId", entryId, 64);
        require("Idempotency-Key", idempotencyKey, 128);
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(command, "command");
        String operation = "wallet:" + action.name().toLowerCase() + ':' + entryId;
        return idempotency.executeForResult(tenantId, idempotencyKey, operation, payloadHash(command),
                () -> apply(tenantId, entryId, idempotencyKey, action, command),
                WalletEntryCommandAcceptance.class);
    }

    private WalletEntryCommandAcceptance apply(String tenantId, String entryId, String idempotencyKey,
                                                Action action, WalletEntryCommand command) {
        WalletEntry current = wallets.findById(tenantId, entryId)
                .orElseThrow(() -> error(BenefitErrorCode.WALLET_ENTRY_NOT_FOUND,
                        "wallet entry was not found"));
        if (command.expectedVersion() != null && command.expectedVersion() != current.version()) {
            throw error(BenefitErrorCode.WALLET_VERSION_CONFLICT,
                    "wallet entry version does not match expectedVersion");
        }
        Instant now = clock.instant();
        boolean unconsumed = current.status() == WalletEntryStatus.UNUSED
                || current.status() == WalletEntryStatus.FROZEN;
        if (current.status() == WalletEntryStatus.EXPIRED
                || (unconsumed && current.expiresAt() != null && current.expiresAt().isBefore(now))) {
            // 失败命令不附带 EXPIRED 写入，避免 409 响应同时产生隐式状态变更。
            throw error(BenefitErrorCode.WALLET_EXPIRED, "wallet entry is expired");
        }
        WalletEntryStatus target = target(action, current.status());
        WalletEntry changed = wallets.compareAndSetStatus(tenantId, entryId, current.status(),
                        current.version(), target, now)
                .orElseThrow(() -> concurrentFailure(tenantId, entryId, action, current.version()));
        String operationNo = operationNo(idempotencyKey);
        appendCashLedger(changed, action, operationNo);
        publishFact(changed, action, operationNo, command, now);
        return new WalletEntryCommandAcceptance(changed.entryId(), changed.status().name(), changed.version());
    }

    private static WalletEntryStatus target(Action action, WalletEntryStatus current) {
        if ((action == Action.FREEZE || action == Action.REDEEM) && current == WalletEntryStatus.USED) {
            throw error(BenefitErrorCode.WALLET_ALREADY_USED, "wallet entry was already used");
        }
        if (current == WalletEntryStatus.REVERSED) {
            throw error(BenefitErrorCode.WALLET_ILLEGAL_TRANSITION,
                    "reversed wallet entries are terminal");
        }
        return switch (action) {
            case FREEZE -> current == WalletEntryStatus.UNUSED ? WalletEntryStatus.FROZEN
                    : illegal(action, current);
            case REDEEM -> current == WalletEntryStatus.UNUSED || current == WalletEntryStatus.FROZEN
                    ? WalletEntryStatus.USED : illegal(action, current);
            case REFUND -> current == WalletEntryStatus.USED ? WalletEntryStatus.REVERSED
                    : illegal(action, current);
        };
    }

    private BenefitApplicationException concurrentFailure(String tenantId, String entryId,
                                                          Action action, long staleVersion) {
        WalletEntry latest = wallets.findById(tenantId, entryId)
                .orElseThrow(() -> error(BenefitErrorCode.WALLET_ENTRY_NOT_FOUND,
                        "wallet entry disappeared during update"));
        if ((action == Action.FREEZE || action == Action.REDEEM)
                && latest.status() == WalletEntryStatus.USED) {
            return error(BenefitErrorCode.WALLET_ALREADY_USED,
                    "wallet entry was concurrently used");
        }
        return error(BenefitErrorCode.WALLET_VERSION_CONFLICT,
                "wallet entry changed concurrently from version " + staleVersion);
    }

    private void appendCashLedger(WalletEntry entry, Action action, String operationNo) {
        if (entry.assetType() != WalletAssetType.CASH_BALANCE || action == Action.FREEZE) return;
        if (entry.faceValueMinor() == null || entry.currency() == null) {
            throw new IllegalStateException("cash wallet entry is missing its issued amount snapshot");
        }
        long delta = action == Action.REDEEM
                ? Math.negateExact(entry.faceValueMinor()) : entry.faceValueMinor();
        wallets.appendCommandBalance(entry, operationNo, action.name(), delta);
    }

    private void publishFact(WalletEntry entry, Action action, String operationNo,
                             WalletEntryCommand command, Instant now) {
        WalletFulfillmentEvent fact = new WalletFulfillmentEvent(entry.entryId(), entry.skuId(),
                entry.skuVersion(), entry.faceValueMinor(), entry.currency(), entry.status().name(),
                action.name(), operationNo, command.reason(), command.merchantRef(), now);
        outbox.enqueue(new MessageEnvelope<>(ids.next("EV"), "FULFILLMENT_WALLET", "1.1",
                entry.tenantId(), now, null, entry.entryId(), fact));
    }

    /** 幂等键可达 128 字符，ledger operation_no 只有 64；使用稳定 SHA-256 派生值。 */
    private static String operationNo(String idempotencyKey) {
        return "WC-" + sha256(idempotencyKey).substring(0, 61);
    }

    private static String payloadHash(WalletEntryCommand command) {
        return sha256(field(command.reason()) + field(command.merchantRef())
                + field(command.expectedVersion() == null ? null : command.expectedVersion().toString()));
    }

    private static String field(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static WalletEntryStatus illegal(Action action, WalletEntryStatus current) {
        throw error(BenefitErrorCode.WALLET_ILLEGAL_TRANSITION,
                "wallet transition " + current + " -> " + action + " is not allowed");
    }

    private static BenefitApplicationException error(BenefitErrorCode code, String message) {
        return new BenefitApplicationException(code, message);
    }

    private static void require(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is required and must be at most "
                    + maxLength + " characters");
        }
    }
}
