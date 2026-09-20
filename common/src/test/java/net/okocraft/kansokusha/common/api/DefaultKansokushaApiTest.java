package net.okocraft.kansokusha.common.api;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.KansokushaApiProvider;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.event.registry.InMemoryRuntimeEventTypeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultKansokushaApiTest {

    private static final Key EVENT_KEY = Key.key("fixture", "block-break");
    private static final Key SERVER_KEY = Key.key("fixture", "server");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private KansokushaApi publishedApi;

    @AfterEach
    void tearDownPublishedApi() {
        if (this.publishedApi != null) {
            KansokushaApiProvider.unpublish(this.publishedApi);
        }
    }

    @Test
    void testExternalProviderCanRegisterAndSubmitValidEventUsingPublicContracts() {
        List<EventSubmission> accepted = new ArrayList<>();
        DefaultKansokushaApi api = api(submission -> {
            accepted.add(submission);
            return true;
        });
        publish(api);
        ExternalProviderFixture provider = new ExternalProviderFixture();

        assertEquals(RegistrationOutcome.REGISTERED, provider.register());
        assertEquals(SubmissionOutcome.ACCEPTED, provider.submit());
        assertEquals(List.of(provider.submission()), accepted);
    }

    @Test
    void testRegistrationIsIdempotentAndRejectsDifferentGeneration() {
        DefaultKansokushaApi api = api(submission -> true);
        EventTypeDefinition first = definition(1);

        assertEquals(RegistrationOutcome.REGISTERED, api.registerEventType(first));
        assertEquals(RegistrationOutcome.ALREADY_REGISTERED, api.registerEventType(definition(1)));
        assertEquals(RegistrationOutcome.CONFLICT, api.registerEventType(definition(2)));
    }

    @Test
    void testUnregisteredEventIsRejectedWithoutIngestion() {
        List<EventSubmission> accepted = new ArrayList<>();
        DefaultKansokushaApi api = api(submission -> {
            accepted.add(submission);
            return true;
        });

        assertEquals(SubmissionOutcome.UNREGISTERED_EVENT_TYPE, api.submit(submission(1)));
        assertTrue(accepted.isEmpty());
    }

    @Test
    void testPayloadGenerationMismatchIsRejectedWithoutIngestion() {
        List<EventSubmission> accepted = new ArrayList<>();
        DefaultKansokushaApi api = api(submission -> {
            accepted.add(submission);
            return true;
        });
        api.registerEventType(definition(1));

        assertEquals(SubmissionOutcome.PAYLOAD_GENERATION_MISMATCH, api.submit(submission(2)));
        assertTrue(accepted.isEmpty());
    }

    @Test
    void testIngestionUnavailableIsReportedAfterValidation() {
        DefaultKansokushaApi api = api(submission -> false);
        api.registerEventType(definition(1));

        assertEquals(SubmissionOutcome.INGESTION_UNAVAILABLE, api.submit(submission(1)));
    }

    @Test
    void testLocalServerKeyIsPresentForSingleServerAndEmptyForProxy() {
        DefaultKansokushaApi singleServerApi = api(submission -> true, SERVER_KEY);
        DefaultKansokushaApi proxyApi = api(submission -> true);

        assertEquals(Optional.of(SERVER_KEY), singleServerApi.localServerKey());
        assertEquals(Optional.empty(), proxyApi.localServerKey());
    }

    @Test
    void testClosedApiRejectsRegistrationAndSubmission() {
        List<EventSubmission> accepted = new ArrayList<>();
        DefaultKansokushaApi api = api(submission -> {
            accepted.add(submission);
            return true;
        });
        api.close();

        assertEquals(RegistrationOutcome.CLOSED, api.registerEventType(definition(1)));
        assertEquals(SubmissionOutcome.CLOSED, api.submit(submission(1)));
        assertTrue(accepted.isEmpty());
    }

    @Test
    void testApiEntryPointPublishesAndUnpublishesOnlyCurrentApi() {
        assertEquals(RegistrationOutcome.CLOSED, Kansokusha.api().registerEventType(definition(1)));
        assertTrue(Kansokusha.api().localServerKey().isEmpty());
        DefaultKansokushaApi api = api(submission -> true);
        DefaultKansokushaApi other = api(submission -> true);
        publish(api);

        assertSame(api, Kansokusha.api());
        assertFalse(KansokushaApiProvider.publish(other));
        assertFalse(KansokushaApiProvider.unpublish(other));
        assertSame(api, Kansokusha.api());
        assertTrue(KansokushaApiProvider.unpublish(api));
        assertTrue(Kansokusha.api().localServerKey().isEmpty());
        assertEquals(SubmissionOutcome.CLOSED, Kansokusha.api().submit(submission(1)));
    }

    private DefaultKansokushaApi api(EventIntake intake) {
        return new DefaultKansokushaApi(new InMemoryRuntimeEventTypeRegistry(), intake);
    }

    private DefaultKansokushaApi api(EventIntake intake, Key localServerKey) {
        return new DefaultKansokushaApi(new InMemoryRuntimeEventTypeRegistry(), intake, localServerKey);
    }

    private void publish(DefaultKansokushaApi api) {
        assertTrue(KansokushaApiProvider.publish(api));
        this.publishedApi = api;
    }

    private static EventTypeDefinition definition(int generation) {
        return new EventTypeDefinition(EVENT_KEY, new PayloadGeneration(generation));
    }

    private static EventSubmission submission(int generation) {
        return new EventSubmission(
            EVENT_KEY, new PayloadGeneration(generation), OCCURRED_AT,
            SERVER_KEY, null, null, null, EventPayload.copyOf(new byte[]{1, 2, 3})
        );
    }

    /** A provider fixture that only depends on public API and event-contract types. */
    private static final class ExternalProviderFixture {

        private final EventTypeDefinition definition = definition(1);
        private final EventSubmission submission = DefaultKansokushaApiTest.submission(1);

        private RegistrationOutcome register() {
            KansokushaApi api = Kansokusha.api();
            return api.registerEventType(this.definition);
        }

        private SubmissionOutcome submit() {
            return Kansokusha.api().submit(this.submission);
        }

        private EventSubmission submission() {
            return this.submission;
        }
    }
}
