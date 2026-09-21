package net.okocraft.kansokusha.paper.fixture;

import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.NamespacedKey;

import java.time.Instant;

public final class ExternalPaperPluginFixture {

    public static final NamespacedKey EVENT_TYPE =
        new NamespacedKey("fixture", "custom_event");

    private ExternalPaperPluginFixture() {
    }

    public static Result registerAndSubmit() {
        var api = Kansokusha.api();
        var definition = PaperKansokusha.eventType(EVENT_TYPE, PayloadGeneration.FIRST);
        var registration = api.registerEventType(definition);
        var submission = new EventSubmission(
            PaperKansokusha.key(EVENT_TYPE),
            PayloadGeneration.FIRST,
            Instant.parse("2026-09-21T00:00:00Z"),
            api.localServerKey().orElseThrow(),
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{1, 2, 3})
        );

        return new Result(api, registration, api.submit(submission), submission);
    }

    public record Result(
        KansokushaApi api,
        RegistrationOutcome registration,
        SubmissionOutcome submissionOutcome,
        EventSubmission submission
    ) {
    }
}
