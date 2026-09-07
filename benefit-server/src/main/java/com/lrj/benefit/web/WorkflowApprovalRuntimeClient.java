package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.service.BenefitApplicationException;
import com.lrj.benefit.contract.BenefitErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 只读查询流程运行态，供权益控制台判断死 PENDING 是否可以安全暴露恢复入口。
 * 业务租户始终显式传递；Bearer 仅用于流程台启用 OAuth2 时认证机器调用方。
 */
@Component
public final class WorkflowApprovalRuntimeClient {
    private static final String DEFINITION_KEY = "benefitSkuGoLive";

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI baseUri;
    private final String bearerToken;
    private final Duration requestTimeout;

    @Autowired
    public WorkflowApprovalRuntimeClient(
            ObjectMapper json,
            @Value("${workflow.http.base-url:http://localhost:8300}") String baseUrl,
            @Value("${workflow.http.bearer-token:}") String bearerToken,
            @Value("${workflow.http.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${workflow.http.request-timeout:PT3S}") Duration requestTimeout) {
        this(json, baseUrl, bearerToken, connectTimeout, requestTimeout,
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
    }

    WorkflowApprovalRuntimeClient(ObjectMapper json, String baseUrl, String bearerToken,
                                  Duration connectTimeout, Duration requestTimeout, HttpClient http) {
        this.json = json;
        this.baseUri = URI.create(normalize(baseUrl));
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("workflow HTTP timeouts must be positive");
        }
        this.requestTimeout = requestTimeout;
        this.http = http;
    }

    /** 查询定义状态与同业务键实例；任何依赖错误都 fail closed，不把未知误报成“无实例”。 */
    public ApprovalRuntimeView inspect(String tenantId, String skuId) {
        String tenant = requireText(tenantId, "tenantId");
        String businessKey = requireText(skuId, "skuId");
        JsonNode availability = get("/api/v1/definitions/" + DEFINITION_KEY + "/availability", tenant);
        JsonNode instances = get("/api/v1/process-instances?definitionKey=" + encode(DEFINITION_KEY)
                + "&businessKey=" + encode(businessKey), tenant);
        if (!instances.isArray()) {
            throw unavailable("workflow process instance response is not an array", null);
        }
        JsonNode first = instances.isEmpty() ? null : instances.get(0);
        return new ApprovalRuntimeView(!instances.isEmpty(), availability.path("status").asText("UNKNOWN"),
                first == null ? null : first.path("processInstanceId").asText(null));
    }

    private JsonNode get(String path, String tenantId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("X-Workflow-Tenant", tenantId)
                .GET();
        if (!bearerToken.isBlank()) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable("workflow runtime returned HTTP " + response.statusCode(), null);
            }
            return json.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("workflow runtime query was interrupted", interrupted);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof BenefitApplicationException applicationFailure) {
                throw applicationFailure;
            }
            throw unavailable("workflow runtime query failed", failure);
        }
    }

    private static BenefitApplicationException unavailable(String message, Throwable cause) {
        BenefitApplicationException error = new BenefitApplicationException(
                BenefitErrorCode.APPROVAL_RUNTIME_UNAVAILABLE, message);
        if (cause != null) error.initCause(cause);
        return error;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalize(String value) {
        String url = requireText(value, "workflow.http.base-url");
        return url.endsWith("/") ? url : url + '/';
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    /** 权益审批运行态的最小只读视图。 */
    public record ApprovalRuntimeView(boolean instancePresent, String definitionStatus,
                                      String processInstanceId) { }
}
