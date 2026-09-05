package com.lrj.benefit.adapters.cache;

import com.lrj.benefit.application.port.out.UserLimitPrecheck;
import com.lrj.benefit.application.port.out.UserLimitRepository;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 用户限额 L2 cache-aside 实现。Redis 异常时回源数据库，绝不把 Redis 当作额度账本。
 */
public final class RedisUserLimitPrecheck implements UserLimitPrecheck {
    private final StringRedisTemplate redis;
    private final UserLimitRepository repository;
    private final Duration ttl;

    public RedisUserLimitPrecheck(StringRedisTemplate redis, UserLimitRepository repository, Duration ttl) {
        this.redis = redis;
        this.repository = repository;
        this.ttl = ttl;
    }

    @Override
    public boolean mayReserve(String tenantId, String subjectRef, String skuId,
                              UserLimitRepository.PeriodType periodType, String periodKey,
                              long quantity, long configuredLimit) {
        long usage;
        try {
            String cached = redis.opsForValue().get(key(tenantId, subjectRef, skuId, periodType, periodKey));
            if (cached != null) usage = Long.parseLong(cached);
            else {
                usage = repository.currentUsage(tenantId, subjectRef, skuId, periodType, periodKey);
                redis.opsForValue().set(key(tenantId, subjectRef, skuId, periodType, periodKey),
                        Long.toString(usage), ttl);
            }
        } catch (RuntimeException redisUnavailable) {
            usage = repository.currentUsage(tenantId, subjectRef, skuId, periodType, periodKey);
        }
        return usage <= configuredLimit - quantity;
    }

    @Override
    public void invalidate(String tenantId, String subjectRef, String skuId,
                           UserLimitRepository.PeriodType periodType, String periodKey) {
        try {
            redis.delete(key(tenantId, subjectRef, skuId, periodType, periodKey));
        } catch (RuntimeException ignored) {
            // 删除失败最多造成短 TTL 内保守拒绝；数据库 CAS 仍防止超发。
        }
    }

    private static String key(String tenantId, String subjectRef, String skuId,
                              UserLimitRepository.PeriodType periodType, String periodKey) {
        // 哈希复合键既避免分隔符碰撞，也避免在 Redis key 中暴露用户稳定引用。
        String material = String.join("\u001f", tenantId, subjectRef, skuId, periodType.name(), periodKey);
        try {
            return "benefit:limit:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
