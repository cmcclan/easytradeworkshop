package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.FeatureFlag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class FeatureFlagClient {
    private static final Logger logger = LoggerFactory.getLogger(FeatureFlagClient.class);
    // This client is invoked on EVERY status poll (~225 req/s projected at 100x
    // peak). Without timeouts, a slow feature-flag-service would block status
    // threads and cascade into thread-pool exhaustion. Keep the budget tight —
    // feature-flag reads are sub-millisecond in the baseline, so a slow read is
    // an anomaly we want to fail fast on rather than wait on.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String featureFlagServiceUrl = System.getenv("FEATURE_FLAG_SERVICE_PROTOCOL") + "://"
            + System.getenv("FEATURE_FLAG_SERVICE_BASE_URL") + ":" + System.getenv("FEATURE_FLAG_SERVICE_PORT")
            + "/v1/flags/";

    public FeatureFlag getFlag(String flagId) {
        logger.info("Getting feature flag with id: {}", flagId);

        // deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(featureFlagServiceUrl + flagId))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapper.readValue(response.body(), FeatureFlag.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(flagId);
        } catch (Exception e) {
            // Fail open to a safe default (flag disabled) rather than propagating an
            // exception up the status-poll path. A feature-flag-service blip must not
            // take down the high-volume status endpoint.
            logger.warn("Feature flag lookup for '{}' failed ({}); defaulting to disabled.", flagId, e.getMessage());
            return unavailable(flagId);
        }
    }

    private FeatureFlag unavailable(String flagId) {
        return new FeatureFlag(flagId, false, flagId, "Flag lookup unavailable; defaulted to disabled.", false, null);
    }
}
