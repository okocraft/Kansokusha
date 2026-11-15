package net.okocraft.kansokusha.paper.notification;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

class DiscordWebhookTest {

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicInteger rateLimitedResponses = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/webhook", exchange -> {
            if (this.rateLimitedResponses.getAndDecrement() > 0) {
                exchange.getResponseHeaders().add("Retry-After", "0.01");
                exchange.sendResponseHeaders(429, -1);
            } else {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                this.requests.add(exchange.getRequestURI().getRawQuery() + " " + body);
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        this.server.createContext("/broken", exchange -> {
            var body = "{\"message\":\"Unknown Webhook\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        this.server.start();
    }

    @AfterEach
    void stopServer() {
        this.server.stop(0);
    }

    @Test
    void testMessagesArePostedInOrderWithoutMentions() {
        try (var webhook = this.webhook("/webhook?thread_id=123")) {
            webhook.send("first");
            webhook.send("\"second\"");
        }

        Assertions.assertEquals(List.of(
            "thread_id=123 {\"content\":\"first\",\"allowed_mentions\":{\"parse\":[]}}",
            "thread_id=123 {\"content\":\"\\\"second\\\"\",\"allowed_mentions\":{\"parse\":[]}}"
        ), this.requests);
        Assertions.assertEquals(List.of(), this.warnings);
    }

    @Test
    void testRateLimitedMessageIsRetriedOnce() {
        this.rateLimitedResponses.set(1);
        try (var webhook = this.webhook("/webhook")) {
            webhook.send("retried");
        }

        Assertions.assertEquals(1, this.requests.size());
        Assertions.assertEquals(List.of(), this.warnings);
    }

    @Test
    void testMessageAfterCloseIsDroppedWithWarning() {
        var webhook = this.webhook("/webhook");
        webhook.close();
        webhook.send("late");

        Assertions.assertEquals(List.of(), this.requests);
        Assertions.assertEquals(List.of("Dropped a Discord webhook message because the queue is full or closed."), this.warnings);
    }

    @Test
    void testErrorResponseIsLogged() {
        try (var webhook = this.webhook("/broken")) {
            webhook.send("lost");
        }

        Assertions.assertEquals(
            List.of("Discord webhook responded with HTTP 404: {\"message\":\"Unknown Webhook\"}"),
            this.warnings
        );
    }

    private DiscordWebhook webhook(String path) {
        var logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                DiscordWebhookTest.this.warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        var url = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + path);
        return new DiscordWebhook(url, logger);
    }
}
