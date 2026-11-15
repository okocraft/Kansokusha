package net.okocraft.kansokusha.paper.notification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Posts plain-text messages to a Discord webhook, one at a time and in order, on a background
 * thread so that the server thread never waits for the network. Delivery is best effort: at most
 * 1000 messages wait to be sent, further messages are dropped with a warning, and messages are not
 * confirmed with {@code wait=true}, so Discord may accept one that it does not save.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class DiscordWebhook implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    // One thread keeps messages in order and makes a rate-limit wait delay the following messages.
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1000), Thread.ofVirtual().name("Kansokusha Discord Webhook").factory());
    private final URI url;
    private final Logger logger;

    /**
     * @param url the webhook URL; add {@code ?thread_id=...} to post into a thread
     */
    public DiscordWebhook(URI url, Logger logger) {
        this.url = url;
        this.logger = logger;
    }

    /**
     * Queues a message. Mentions in it never ping anyone, so player names cannot ping users or roles.
     */
    public void send(String content) {
        var body = new JsonObject();
        body.addProperty("content", content);
        var allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);
        var request = HttpRequest.newBuilder(this.url)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        try {
            this.executor.execute(() -> this.post(request));
        } catch (RejectedExecutionException e) {
            this.logger.warning("Dropped a Discord webhook message because the queue is full or closed.");
        }
    }

    private void post(HttpRequest request) {
        try {
            var response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                // Discord gives the wait in seconds, possibly with a fraction such as "1.5".
                var seconds = response.headers().firstValue("Retry-After").map(Double::parseDouble).orElse(1.0);
                Thread.sleep((long) (seconds * 1000));
                response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
            }
            if (response.statusCode() / 100 != 2) {
                this.logger.warning("Discord webhook responded with HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException | NumberFormatException e) {
            this.logger.log(Level.WARNING, "Failed to send a Discord webhook message.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits up to five seconds for queued messages, then drops the rest and releases the HTTP client.
     */
    @Override
    public void close() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            this.client.shutdownNow();
        }
    }
}
