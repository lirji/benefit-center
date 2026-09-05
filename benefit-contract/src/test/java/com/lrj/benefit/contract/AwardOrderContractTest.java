package com.lrj.benefit.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发奖订单查询契约测试，确保前端深链所需的领取人引用不会从响应契约中丢失。
 */
class AwardOrderContractTest {

    /**
     * recipientRef 必须是订单响应的必填字段，避免真实订单无法跳转到对应券包。
     */
    @Test
    void awardOrderRequiresRecipientRef() {
        Map<String, Object> document = loadOpenApi();
        Map<String, Object> components = castMap(document.get("components"));
        Map<String, Object> schemas = castMap(components.get("schemas"));
        Map<String, Object> awardOrder = castMap(schemas.get("AwardOrder"));
        List<String> required = castList(awardOrder.get("required"));
        Map<String, Object> properties = castMap(awardOrder.get("properties"));

        assertThat(required).contains("recipientRef");
        assertThat(properties).containsKey("recipientRef");
        assertThat(castMap(properties.get("recipientRef"))).containsEntry("type", "string");
    }

    private Map<String, Object> loadOpenApi() {
        try (InputStream input = getClass().getResourceAsStream("/openapi/benefit-center-v1.yaml")) {
            assertThat(input).isNotNull();
            return castMap(new Yaml().load(input));
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取 benefit-center OpenAPI 契约", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object value) {
        return (List<String>) value;
    }
}
