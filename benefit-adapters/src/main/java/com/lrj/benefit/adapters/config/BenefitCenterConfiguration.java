package com.lrj.benefit.adapters.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.adapters.channel.*;
import com.lrj.benefit.adapters.cache.CaffeineSkuTemplateCache;
import com.lrj.benefit.adapters.cache.DatabaseUserLimitPrecheck;
import com.lrj.benefit.adapters.cache.RedisUserLimitPrecheck;
import com.lrj.benefit.adapters.jdbc.*;
import com.lrj.benefit.adapters.messaging.KafkaEventPublisher;
import com.lrj.benefit.adapters.messaging.OutboxRelay;
import com.lrj.benefit.adapters.support.UuidIdGenerator;
import com.lrj.benefit.adapters.support.SingleShardRouter;
import com.lrj.benefit.application.port.in.*;
import com.lrj.benefit.application.port.out.*;
import com.lrj.benefit.application.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Configuration
public class BenefitCenterConfiguration {
    @Bean IdGenerator idGenerator() { return new UuidIdGenerator(); }
    @Bean ShardRouter shardRouter() { return new SingleShardRouter(); }
    @Bean Clock benefitClock() { return Clock.systemUTC(); }
    @Bean UnitOfWork unitOfWork(PlatformTransactionManager tx) {
        return new SpringUnitOfWork(new TransactionTemplate(tx));
    }
    @Bean AwardRepository awardRepository(JdbcTemplate jdbc) { return new JdbcAwardRepository(jdbc); }
    @Bean CellRouter cellRouter(JdbcTemplate jdbc, @Value("${benefit.cell.id:cell-0}") String localCell) {
        return new JdbcCellRouter(jdbc, localCell);
    }
    @Bean BenefitCatalogRepository catalogRepository(JdbcTemplate jdbc) { return new JdbcCatalogRepository(jdbc); }
    @Bean SkuTemplateCache skuTemplateCache(BenefitCatalogRepository catalog,
                                            @Value("${benefit.cache.template-ttl:PT20S}") Duration ttl,
                                            @Value("${benefit.cache.template-maximum-size:10000}") long maximumSize) {
        return new CaffeineSkuTemplateCache(catalog, ttl, maximumSize);
    }
    @Bean InventoryRepository inventoryRepository(JdbcTemplate jdbc, IdGenerator ids) {
        return new JdbcInventoryRepository(jdbc, ids);
    }
    @Bean OperationRepository operationRepository(JdbcTemplate jdbc, IdGenerator ids) {
        return new JdbcOperationRepository(jdbc, ids);
    }
    @Bean LedgerRepository ledgerRepository(JdbcTemplate jdbc) { return new JdbcLedgerRepository(jdbc); }
    @Bean WalletRepository walletRepository(JdbcTemplate jdbc, IdGenerator ids, Clock clock) {
        return new JdbcWalletRepository(jdbc, ids, clock);
    }
    @Bean UserLimitRepository userLimitRepository(JdbcTemplate jdbc, Clock clock) {
        return new JdbcUserLimitRepository(jdbc, clock);
    }
    @Bean UserLimitPrecheck userLimitPrecheck(
            UserLimitRepository repository, StringRedisTemplate redis,
            @Value("${benefit.cache.redis-enabled:true}") boolean redisEnabled,
            @Value("${benefit.cache.user-limit-ttl:PT30S}") Duration ttl) {
        return redisEnabled ? new RedisUserLimitPrecheck(redis, repository, ttl)
                : new DatabaseUserLimitPrecheck(repository);
    }
    @Bean IdempotentCommandExecutor idempotentCommandExecutor(JdbcTemplate jdbc, UnitOfWork unitOfWork,
                                                               Clock clock, ObjectMapper json) {
        return new JdbcIdempotentCommandExecutor(jdbc, unitOfWork, clock, json);
    }
    @Bean OutboxRepository outboxRepository(JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcOutboxRepository(jdbc, json);
    }
    @Bean InboxRepository inboxRepository(JdbcTemplate jdbc) { return new JdbcInboxRepository(jdbc); }
    @Bean RemediationRepository remediationRepository(JdbcTemplate jdbc) {
        return new JdbcRemediationRepository(jdbc);
    }
    @Bean CodeAssetRepository codeAssetRepository(JdbcTemplate jdbc) { return new JdbcCodeAssetRepository(jdbc); }
    @Bean PhysicalFulfillmentRepository physicalFulfillmentRepository(JdbcTemplate jdbc) {
        return new JdbcPhysicalFulfillmentRepository(jdbc);
    }
    @Bean CenterCodeAdapter centerCodeAdapter(CodeAssetRepository repository) {
        return new CenterCodeAdapter(repository);
    }
    @Bean PhysicalFulfillmentAdapter physicalFulfillmentAdapter(PhysicalFulfillmentRepository repository) {
        return new PhysicalFulfillmentAdapter(repository);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = {"benefit.channel.real-enabled", "benefit.channel.http.enabled"}, havingValue = "true")
    ConfigurableHttpChannelAdapter configurableHttpChannelAdapter(
            ObjectMapper json, Clock clock,
            @Value("${benefit.channel.http.code}") String code,
            @Value("${benefit.channel.http.base-url}") String baseUrl,
            @Value("${benefit.channel.http.secret}") String secret,
            @Value("${benefit.channel.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${benefit.channel.http.read-timeout-ms:5000}") int readTimeoutMs) {
        return new ConfigurableHttpChannelAdapter(code, baseUrl, secret, json, clock,
                new com.lrj.benefit.domain.model.AdapterCapabilities(true, true, true, false),
                connectTimeoutMs, readTimeoutMs);
    }
    @Bean ChannelAdapterRegistry channelAdapterRegistry(List<ChannelAdapter> adapters) {
        return new DefaultChannelAdapterRegistry(adapters);
    }
    @Bean JdbcCatalogAdminService catalogAdminUseCase(JdbcTemplate jdbc, UnitOfWork unitOfWork, IdGenerator ids,
                                                      ChannelAdapterRegistry adapters,
                                                      SkuTemplateCache templateCache,
                                                      BenefitCatalogRepository catalog, OutboxRepository outbox,
                                                      InboxRepository inbox, Clock clock) {
        return new JdbcCatalogAdminService(jdbc, unitOfWork, ids, adapters, templateCache, catalog,
                outbox, inbox, clock);
    }

    @Bean ConsoleQueryUseCase consoleQueryUseCase(JdbcTemplate jdbc, Clock clock) {
        return new JdbcConsoleQueryService(jdbc, clock);
    }
    @Bean WalletQueryUseCase walletQueryUseCase(JdbcTemplate jdbc) {
        return new JdbcWalletQueryService(jdbc);
    }
    @Bean WalletEntryCommandUseCase walletEntryCommandUseCase(
            WalletRepository wallets, IdempotentCommandExecutor idempotency,
            OutboxRepository outbox, IdGenerator ids, Clock clock) {
        return new WalletEntryCommandApplicationService(wallets, idempotency, outbox, ids, clock);
    }
    @Bean AwardApplicationService awardApplicationService(AwardRepository awards,
                                                          BenefitCatalogRepository catalog,
                                                          SkuTemplateCache templateCache,
                                                          InventoryRepository inventory,
                                                          UserLimitRepository userLimits,
                                                          UserLimitPrecheck limitPrecheck,
                                                          OperationRepository operations,
                                                          OutboxRepository outbox,
                                                          UnitOfWork unitOfWork,
                                                          IdGenerator ids,
                                                          Clock clock,
                                                          SkuAcceptanceRepository skuAcceptance) {
        return new AwardApplicationService(awards, catalog, templateCache, inventory, userLimits,
                limitPrecheck, operations, outbox, unitOfWork, ids, clock, skuAcceptance);
    }
    @Bean FulfillmentApplicationService fulfillmentApplicationService(
            AwardRepository awards, OperationRepository operations, BenefitCatalogRepository catalog,
            InventoryRepository inventory, LedgerRepository ledger, WalletRepository wallets,
            UserLimitRepository userLimits, UserLimitPrecheck limitPrecheck, OutboxRepository outbox,
            RemediationRepository remediations, ChannelAdapterRegistry adapters, UnitOfWork unitOfWork,
            IdGenerator ids, Clock clock,
            @Value("${benefit.worker.lease-duration:PT30S}") Duration leaseDuration) {
        return new FulfillmentApplicationService(awards, operations, catalog, inventory, ledger, wallets,
                userLimits, limitPrecheck, outbox, remediations, adapters, unitOfWork, ids, clock, leaseDuration);
    }
    @Bean RemediationApplicationService remediationApplicationService(
            RemediationRepository remediations, AwardRepository awards, OperationRepository operations,
            BenefitCatalogRepository catalog, InventoryRepository inventory, UserLimitRepository userLimits,
            UserLimitPrecheck limitPrecheck, OutboxRepository outbox,
            UnitOfWork unitOfWork, IdGenerator ids, Clock clock) {
        return new RemediationApplicationService(remediations, awards, operations, catalog, inventory,
                userLimits, limitPrecheck, outbox, unitOfWork, ids, clock);
    }
    @Bean EventPublisher eventPublisher(KafkaTemplate<String, String> kafka,
                                        @Value("${benefit.kafka.fulfillment-topic:benefit.fulfillment.v1}") String topic,
                                        @Value("${benefit.kafka.remediation-topic:benefit.remediation.result.v1}") String remediationTopic,
                                        @Value("${benefit.kafka.sku-template-topic:benefit.sku-template.v1}") String skuTemplateTopic,
                                        @Value("${workflow.kafka.start-topic:workflow.command.start.v1}") String workflowStartTopic,
                                        @Value("${workflow.kafka.applied-topic:workflow.action.applied.v1}") String workflowAppliedTopic,
                                        @Value("${workflow.kafka.source-signing-keys:}") String workflowSigningKeys) {
        return new KafkaEventPublisher(kafka, Map.of("default", topic,
                "REMEDIATION_DISPATCHED", remediationTopic,
                "REMEDIATION_RESULT", remediationTopic,
                "SKU_TEMPLATE_CHANGED", skuTemplateTopic,
                "workflow.command.start.v1", workflowStartTopic,
                "workflow.action.applied.v1", workflowAppliedTopic), workflowSigningKeys);
    }
    @Bean OutboxRelay outboxRelay(OutboxRepository outbox, EventPublisher publisher, Clock clock,
                                  @Value("${benefit.outbox.max-attempts:10}") int maxAttempts) {
        return new OutboxRelay(outbox, publisher, clock, maxAttempts);
    }
    @Bean ChannelCallbackVerifier channelCallbackVerifier(
            Clock clock, @Value("${benefit.callback.secrets:}") String configuredSecrets) {
        Map<String, String> secrets = new HashMap<>();
        if (configuredSecrets != null && !configuredSecrets.isBlank()) {
            for (String pair : configuredSecrets.split(",")) {
                String[] values = pair.trim().split("=", 2);
                if (values.length != 2) throw new IllegalArgumentException("invalid callback secret mapping");
                secrets.put(values[0], values[1]);
            }
        }
        return new ChannelCallbackVerifier(secrets, clock, Duration.ofMinutes(5));
    }
    @Bean ChannelCallbackService channelCallbackService(ChannelCallbackVerifier verifier, JdbcTemplate jdbc,
                                                         UnitOfWork unitOfWork, ObjectMapper json) {
        return new ChannelCallbackService(verifier, jdbc, unitOfWork, json);
    }
}
