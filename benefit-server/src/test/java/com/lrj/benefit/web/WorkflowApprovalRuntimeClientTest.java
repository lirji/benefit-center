package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.service.BenefitApplicationException;
import com.lrj.benefit.contract.BenefitErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 流程运行态 BFF 的租户透传与 fail-closed 行为测试。 */
class WorkflowApprovalRuntimeClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void reportsMissingInstanceOnlyAfterBothWorkflowQueriesSucceed() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/definitions/benefitSkuGoLive/availability",
                exchange -> respond(exchange, 200, "{\"status\":\"DEPLOYED\"}"));
        server.createContext("/api/v1/process-instances", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Workflow-Tenant")).isEqualTo("tenant-a");
            assertThat(exchange.getRequestURI().getQuery()).contains("businessKey=SKU-1");
            respond(exchange, 200, "[]");
        });
        server.start();

        WorkflowApprovalRuntimeClient client = client();
        assertThat(client.inspect("tenant-a", "SKU-1"))
                .isEqualTo(new WorkflowApprovalRuntimeClient.ApprovalRuntimeView(false, "DEPLOYED", null));
    }

    @Test
    void dependencyFailureNeverLooksLikeMissingInstance() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/definitions/benefitSkuGoLive/availability",
                exchange -> respond(exchange, 503, "{}"));
        server.start();

        assertThatThrownBy(() -> client().inspect("tenant-a", "SKU-1"))
                .isInstanceOfSatisfying(BenefitApplicationException.class,
                        error -> assertThat(error.code()).isEqualTo(BenefitErrorCode.APPROVAL_RUNTIME_UNAVAILABLE));
    }

    private WorkflowApprovalRuntimeClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new WorkflowApprovalRuntimeClient(new ObjectMapper(), baseUrl, "", Duration.ofSeconds(1),
                Duration.ofSeconds(1), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
