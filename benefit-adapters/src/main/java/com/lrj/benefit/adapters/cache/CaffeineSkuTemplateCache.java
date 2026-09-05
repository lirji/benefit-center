package com.lrj.benefit.adapters.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lrj.benefit.application.port.out.BenefitCatalogRepository;
import com.lrj.benefit.application.port.out.SkuTemplateCache;
import com.lrj.benefit.domain.model.BenefitSku;

import java.time.Duration;
import java.util.Optional;

/**
 * 模板 L1 缓存：当前世代指针短 TTL，模板实体以 tenant+sku+version 为不可变键。
 */
public final class CaffeineSkuTemplateCache implements SkuTemplateCache {
    private final BenefitCatalogRepository source;
    private final Cache<CurrentKey, Optional<Long>> generations;
    private final Cache<VersionKey, BenefitSku> versions;

    public CaffeineSkuTemplateCache(BenefitCatalogRepository source, Duration ttl, long maximumSize) {
        this.source = source;
        this.generations = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maximumSize).build();
        this.versions = Caffeine.newBuilder().maximumSize(maximumSize).build();
    }

    @Override
    public Optional<BenefitSku> findCurrent(String tenantId, String skuId) {
        CurrentKey currentKey = new CurrentKey(tenantId, skuId);
        Optional<Long> generation = generations.get(currentKey, ignored -> source.findSku(tenantId, skuId)
                .map(template -> {
                    versions.put(new VersionKey(tenantId, skuId, template.version()), template);
                    return template.version();
                }));
        return generation.flatMap(version -> {
            VersionKey key = new VersionKey(tenantId, skuId, version);
            BenefitSku cached = versions.getIfPresent(key);
            if (cached != null) return Optional.of(cached);
            // 版本缓存被容量淘汰时必须回源，不允许把存在的模板误判为不存在。
            Optional<BenefitSku> loaded = source.findSkuVersion(tenantId, skuId, version);
            loaded.ifPresent(value -> versions.put(key, value));
            return loaded;
        });
    }

    @Override
    public void invalidateCurrent(String tenantId, String skuId) {
        generations.invalidate(new CurrentKey(tenantId, skuId));
    }

    private record CurrentKey(String tenantId, String skuId) {}
    private record VersionKey(String tenantId, String skuId, long version) {}
}
