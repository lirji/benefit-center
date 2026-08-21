package com.lrj.benefit.adapters.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.out.ChannelAdapter;
import com.lrj.benefit.domain.model.AdapterCapabilities;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/** Reference signed HTTP adapter. A production channel must pass its own contract/sandbox suite. */
public final class ConfigurableHttpChannelAdapter implements ChannelAdapter {
    private final String channelCode;
    private final RestClient http;
    private final String secret;
    private final ObjectMapper json;
    private final Clock clock;
    private final AdapterCapabilities capabilities;

    public ConfigurableHttpChannelAdapter(String channelCode, String baseUrl, String secret,
                                          ObjectMapper json, Clock clock, AdapterCapabilities capabilities,
                                          int connectTimeoutMs, int readTimeoutMs) {
        this.channelCode = channelCode;
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("channel timeouts must be positive");
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.secret = secret;
        this.json = json;
        this.clock = clock;
        this.capabilities = capabilities;
    }

    @Override public String channelCode() { return channelCode; }
    @Override public AdapterCapabilities capabilities() { return capabilities; }
    @Override public ChannelResult issue(ChannelCommand command) { return invoke("/issue", command); }
    @Override public ChannelResult query(ChannelCommand command) { return invoke("/query", command); }
    @Override public ChannelResult reverse(ChannelCommand command) { return invoke("/reverse", command); }

    private ChannelResult invoke(String path, ChannelCommand command) {
        try {
            String body = json.writeValueAsString(command);
            String timestamp = Long.toString(clock.instant().getEpochSecond());
            String signature = sign(timestamp + '\n' + body);
            JsonNode response = http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.operationNo())
                    .header("X-Signature-Timestamp", timestamp)
                    .header("X-Signature", signature)
                    .body(body).retrieve().body(JsonNode.class);
            if (response == null) return new ChannelResult(ChannelResult.ResultType.UNKNOWN, null,
                    "EMPTY_RESPONSE", null);
            return new ChannelResult(ChannelResult.ResultType.valueOf(response.path("result").asText("UNKNOWN")),
                    response.path("providerReference").asText(null), response.path("errorCode").asText(null),
                    response.path("message").asText(null));
        } catch (org.springframework.web.client.HttpClientErrorException clientError) {
            if (clientError.getStatusCode().value() == 429) {
                return new ChannelResult(ChannelResult.ResultType.RETRYABLE_FAILURE, null,
                        "HTTP_429", null);
            }
            return new ChannelResult(ChannelResult.ResultType.FINAL_FAILURE, null,
                    "HTTP_" + clientError.getStatusCode().value(), null);
        } catch (Exception uncertain) {
            return new ChannelResult(ChannelResult.ResultType.UNKNOWN, null, "TRANSPORT_UNKNOWN",
                    uncertain.getClass().getSimpleName());
        }
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
