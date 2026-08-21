package com.lrj.benefit.application.service;

import com.lrj.benefit.contract.AwardIntent;
import com.lrj.benefit.contract.AwardItemIntent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

/** Stable cross-entry-point request hash; maps are sorted and display strings are never used. */
public final class AwardIntentHasher {
    public String hash(AwardIntent intent) {
        StringBuilder value = new StringBuilder(512);
        appendField(value, intent.schemaVersion());
        appendField(value, intent.sourceSystem());
        appendField(value, intent.sourceRequestId());
        appendField(value, intent.sourceBusinessNo());
        appendField(value, intent.recipientRef());
        appendField(value, intent.partialPolicy());
        if (intent.decision() == null) {
            appendField(value, null); appendField(value, null); appendField(value, null);
        } else {
            appendField(value, intent.decision().decisionId());
            appendField(value, intent.decision().activityId());
            appendField(value, intent.decision().activityVersion());
        }
        appendField(value, intent.items().size());
        intent.items().stream().sorted(Comparator.comparing(AwardItemIntent::clientItemId)).forEach(item -> {
            appendField(value, item.clientItemId());
            appendField(value, item.benefitSkuId());
            appendField(value, item.benefitType());
            appendField(value, item.quantity());
            appendField(value, item.amountMinor());
            appendField(value, item.currency());
            appendMap(value, item.metadata());
        });
        appendMap(value, intent.trace());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void appendMap(StringBuilder target, Map<String, String> values) {
        appendField(target, values.size());
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    appendField(target, entry.getKey());
                    appendField(target, entry.getValue());
                });
    }

    private static void appendField(StringBuilder target, Object value) {
        if (value == null) {
            target.append("-1:");
            return;
        }
        String text = value.toString();
        target.append(text.length()).append(':').append(text);
    }
}
