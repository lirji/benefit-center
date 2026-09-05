package com.lrj.benefit.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定 Slice 4c 的 HTTP、读模型与事实事件契约，避免实现和文档漂移。 */
class WalletCommandContractTest {

    @Test
    void openApiPublishesSynchronousWalletCommandsAndVersionedReadModel() {
        Map<String, Object> api = yaml("openapi/benefit-center-v1.yaml");
        Map<String, Object> paths = map(api.get("paths"));
        for (String action : List.of("freeze", "redeem", "refund")) {
            Map<String, Object> post = map(map(paths.get(
                    "/openapi/v1/wallet-entries/{entryId}:" + action)).get("post"));
            assertThat(post.get("security").toString()).contains("benefit.admin");
            assertThat(map(post.get("responses"))).containsKeys("202", "404", "409");
        }
        assertThat(paths).doesNotContainKey("/openapi/v1/wallet-entries/{entryId}:unfreeze");

        Map<String, Object> schemas = map(map(api.get("components")).get("schemas"));
        Map<String, Object> command = map(schemas.get("WalletEntryCommand"));
        assertThat(map(map(command.get("properties")).get("expectedVersion")))
                .containsEntry("format", "int64");
        Map<String, Object> view = map(schemas.get("WalletEntryView"));
        assertThat(list(view.get("required"))).contains("version");
        assertThat(map(map(view.get("properties")).get("version"))).containsEntry("format", "int64");

        List<Object> codes = list(map(map(map(schemas.get("Error")).get("properties")).get("code")).get("enum"));
        assertThat(codes).contains("WALLET_ENTRY_NOT_FOUND", "WALLET_ILLEGAL_TRANSITION",
                "WALLET_ALREADY_USED", "WALLET_VERSION_CONFLICT", "WALLET_EXPIRED");
    }

    @Test
    void asyncApiPublishesDistinctWalletFacts() {
        Map<String, Object> api = yaml("asyncapi/benefit-center-v1.yaml");
        Map<String, Object> components = map(api.get("components"));
        Map<String, Object> messages = map(components.get("messages"));
        Map<String, Object> envelope = map(messages.get("FulfillmentEnvelope"));
        List<Object> allOf = list(map(envelope.get("payload")).get("allOf"));
        Map<String, Object> eventProperties = map(map(allOf.get(1)).get("properties"));
        assertThat(list(map(eventProperties.get("eventType")).get("enum")))
                .contains("FULFILLMENT_WALLET");

        Map<String, Object> schemas = map(components.get("schemas"));
        List<Object> entryTypes = list(map(map(map(schemas.get("FulfillmentEvent"))
                .get("properties")).get("entryType")).get("enum"));
        assertThat(entryTypes).contains("FREEZE", "REDEEM", "REFUND");
        assertThat(list(map(schemas.get("WalletFulfillmentEvent")).get("required")))
                .contains("walletEntryId", "skuId", "skuVersion", "status", "entryType");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> yaml(String resource) {
        try (InputStream input = WalletCommandContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing contract: " + resource);
            return map(new Yaml().load(input));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read contract: " + resource, failure);
        }
    }
}
