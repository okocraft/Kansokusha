package net.okocraft.kansokusha.paper.builtin;

import com.destroystokyo.paper.event.server.WhitelistToggleEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.event.server.WhitelistStateUpdateEvent;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class PaperWhitelistChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID PROFILE_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174030");

    @Test
    void testGlobalToggleAndProfileUpdateUseDistinctActions() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var enabled = new AtomicBoolean(false);
        var listener = PaperWhitelistChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock(),
            enabled::get
        );

        var toggle = Mockito.mock(WhitelistToggleEvent.class);
        Mockito.when(toggle.isEnabled()).thenReturn(true);
        listener.captureToggle(toggle);
        enabled.set(true);
        listener.finalizeToggle(toggle);

        var toggleSubmission = onlySubmission(api);
        Assertions.assertEquals(PaperWhitelistChangeListener.EVENT_TYPE, toggleSubmission.eventType());
        Assertions.assertNull(toggleSubmission.worldKey());
        Assertions.assertNull(toggleSubmission.position());
        Assertions.assertNull(toggleSubmission.subject());
        var togglePayload = PaperPayloadNbtCodec.decode(toggleSubmission.payload());
        Assertions.assertEquals("global_toggle", string(togglePayload, "action"));
        Assertions.assertFalse(togglePayload.getBooleanOr("before_enabled", true));
        Assertions.assertTrue(togglePayload.getBooleanOr("after_enabled", false));
        Assertions.assertEquals("whitelist_toggle", string(togglePayload, "source_event"));

        var profile = Mockito.mock(PlayerProfile.class);
        Mockito.when(profile.getId()).thenReturn(PROFILE_ID);
        Mockito.when(profile.getName()).thenReturn("Alice");
        var player = Mockito.mock(OfflinePlayer.class);
        Mockito.when(player.isWhitelisted()).thenReturn(false);

        var profileEvent = Mockito.mock(WhitelistStateUpdateEvent.class);
        Mockito.when(profileEvent.getPlayerProfile()).thenReturn(profile);
        Mockito.when(profileEvent.getPlayer()).thenReturn(player);
        Mockito.when(profileEvent.getStatus())
            .thenReturn(WhitelistStateUpdateEvent.WhitelistStatus.ADDED);
        Mockito.when(profileEvent.isCancelled()).thenReturn(false);

        listener.captureProfile(profileEvent);
        listener.finalizeProfile(profileEvent);

        var profileSubmission = onlySubmission(api);
        Assertions.assertEquals(PaperWhitelistChangeListener.EVENT_TYPE, profileSubmission.eventType());
        Assertions.assertNull(profileSubmission.worldKey());
        Assertions.assertNull(profileSubmission.position());
        Assertions.assertNull(profileSubmission.subject());
        var profilePayload = PaperPayloadNbtCodec.decode(profileSubmission.payload());
        Assertions.assertEquals("profile_add", string(profilePayload, "action"));
        Assertions.assertFalse(profilePayload.getBooleanOr("before_whitelisted", true));
        Assertions.assertTrue(profilePayload.getBooleanOr("after_whitelisted", false));
        Assertions.assertEquals(PROFILE_ID.toString(), string(profilePayload, "profile_uuid"));
        Assertions.assertEquals("Alice", string(profilePayload, "profile_name"));
        Assertions.assertEquals(
            "whitelist_state_update",
            string(profilePayload, "source_event")
        );
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledProfileUpdateIsNotRecorded() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api, false);
        var event = profileEvent(false, WhitelistStateUpdateEvent.WhitelistStatus.ADDED);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.captureProfile(event);
        listener.finalizeProfile(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testProfileNoOpsAreNotRecordedAsStateChanges() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api, false);

        var alreadyAdded = profileEvent(
            true,
            WhitelistStateUpdateEvent.WhitelistStatus.ADDED
        );
        listener.captureProfile(alreadyAdded);
        listener.finalizeProfile(alreadyAdded);

        var alreadyRemoved = profileEvent(
            false,
            WhitelistStateUpdateEvent.WhitelistStatus.REMOVED
        );
        listener.captureProfile(alreadyRemoved);
        listener.finalizeProfile(alreadyRemoved);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testGlobalToggleNoOpIsNotRecorded() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api, true);
        var toggle = Mockito.mock(WhitelistToggleEvent.class);
        Mockito.when(toggle.isEnabled()).thenReturn(true);

        listener.captureToggle(toggle);
        listener.finalizeToggle(toggle);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static PaperWhitelistChangeListener listener(
        PaperBlockEventTestSupport.RecordingApi api,
        boolean whitelistEnabled
    ) {
        return PaperWhitelistChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock(),
            () -> whitelistEnabled
        );
    }

    private static WhitelistStateUpdateEvent profileEvent(
        boolean whitelisted,
        WhitelistStateUpdateEvent.WhitelistStatus status
    ) {
        var profile = Mockito.mock(PlayerProfile.class);
        Mockito.when(profile.getId()).thenReturn(PROFILE_ID);
        Mockito.when(profile.getName()).thenReturn("Alice");

        var player = Mockito.mock(OfflinePlayer.class);
        Mockito.when(player.isWhitelisted()).thenReturn(whitelisted);

        var event = Mockito.mock(WhitelistStateUpdateEvent.class);
        Mockito.when(event.getPlayerProfile()).thenReturn(profile);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getStatus()).thenReturn(status);
        Mockito.when(event.isCancelled()).thenReturn(false);
        return event;
    }

    private static Clock fixedClock() {
        return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
    }

    private static net.okocraft.kansokusha.api.event.EventSubmission onlySubmission(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }

    private static String string(CompoundTag payload, String key) {
        return payload.getString(key).orElseThrow();
    }
}
