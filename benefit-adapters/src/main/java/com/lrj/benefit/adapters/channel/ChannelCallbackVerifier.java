package com.lrj.benefit.adapters.channel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

public final class ChannelCallbackVerifier {
    private final Map<String, String> secrets;
    private final Clock clock;
    private final Duration allowedSkew;

    public ChannelCallbackVerifier(Map<String, String> secrets, Clock clock, Duration allowedSkew) {
        this.secrets = Map.copyOf(secrets);
        this.clock = clock;
        this.allowedSkew = allowedSkew;
    }

    public void verify(String channelCode, String timestamp, String nonce, String body, String signature) {
        String secret = secrets.get(channelCode);
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("callback channel is not configured");
        Instant issuedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (Duration.between(issuedAt, clock.instant()).abs().compareTo(allowedSkew) > 0) {
            throw new IllegalArgumentException("callback timestamp is outside the replay window");
        }
        String canonical = timestamp + '\n' + nonce + '\n' + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] actual = Base64.getDecoder().decode(signature);
            if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException("invalid callback signature");
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception cryptoFailure) {
            throw new IllegalStateException("callback signature verification failed", cryptoFailure);
        }
    }
}
