package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinSettlementRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.StandardResponse;
import com.dynatrace.easytrade.creditcardorderservice.models.StatusType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;

/**
 * Settles pending bitcoin payments asynchronously, off the request path.
 *
 * <p>The external bitcoin-payment-service is expected to be slow (multi-second),
 * mirroring third-party-service. To keep this from cascading into request threads
 * or piling up when the provider is degraded, all outbound calls are bounded by a
 * connect timeout and a per-request timeout. On timeout/error the payment is left
 * pending so a future scheduler pass retries it; on a definitive negative response
 * it is marked failed.</p>
 *
 * <p>Scaling note: when running more than one replica of this service, this
 * scheduler must run as a singleton worker or the pending-order query must take a
 * row lock (e.g. {@code WITH (UPDLOCK, READPAST)}) so the same payment is not
 * settled twice. See BITCOIN_PAYMENT_DESIGN.md.</p>
 */
@Service
public class BitcoinPaymentScheduler extends BaseScheduler {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentScheduler.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final DatabaseHelper dbHelper;
    private final String bitcoinPaymentService = System.getenv("BITCOIN_PAYMENT_SERVICE_HOSTANDPORT");
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public BitcoinPaymentScheduler(DatabaseHelper dbHelper) {
        super("bitcoin-payment",
                Integer.parseInt(System.getenv("BITCOIN_PAYMENT_DELAY")),
                Integer.parseInt(System.getenv("BITCOIN_PAYMENT_RATE")));
        this.dbHelper = dbHelper;
    }

    @Override
    protected void run() {
        logger.info("Running BitcoinPaymentScheduler task!");

        try (Connection conn = dbHelper.getConnection()) {
            List<String> pending = dbHelper.getOrderIdsWithStatus(conn, StatusType.BITCOIN_PAYMENT_PENDING);
            ObjectMapper mapper = new ObjectMapper();

            for (String orderId : pending) {
                settlePayment(conn, mapper, orderId);
            }
        } catch (Exception e) {
            logger.error("Caught exception while running BitcoinPaymentScheduler's task: " + e.getMessage(), e);
        }

        logger.info("Finished BitcoinPaymentScheduler task!");

        randomFixedRatePlusSleep();
    }

    private void settlePayment(Connection conn, ObjectMapper mapper, String orderId) {
        try {
            String details = dbHelper.getLatestStatusDetails(conn, orderId);
            if (details == null) {
                logger.warn("No settlement details found for pending bitcoin payment on order: {}", orderId);
                return;
            }
            BitcoinSettlementRequest body = mapper.readValue(details, BitcoinSettlementRequest.class);

            logger.info("Settling bitcoin payment for order: {}", orderId);

            // deepcode ignore Ssrf: trusted environment variable
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("http://%s/v1/payments", bitcoinPaymentService)))
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            StandardResponse standardResponse = mapper.readValue(response.body(), StandardResponse.class);

            if (standardResponse.statusCode() == HttpStatus.OK.value()) {
                logger.info("Bitcoin payment confirmed for order: {}", orderId);
                dbHelper.insertNewStatus(orderId, StatusType.BITCOIN_PAYMENT_CONFIRMED);
            } else {
                logger.warn("Bitcoin payment rejected for order {}. Status code {}, message: {}",
                        orderId, standardResponse.statusCode(), standardResponse.message());
                dbHelper.insertNewStatus(orderId, StatusType.BITCOIN_PAYMENT_FAILED,
                        "Provider rejected payment: " + standardResponse.message());
            }
        } catch (java.net.http.HttpTimeoutException e) {
            // Leave the payment pending so a later pass retries it rather than failing
            // it on a transient provider slowdown.
            logger.warn("Bitcoin payment provider timed out for order {}; will retry on next pass.", orderId);
        } catch (Exception e) {
            logger.error("Failed to settle bitcoin payment for order " + orderId + "; will retry on next pass.", e);
        }
    }
}
