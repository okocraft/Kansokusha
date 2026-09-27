package net.okocraft.kansokusha.testplugin;

import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

/**
 * Submits one event through the public API and verifies the API is closed after the server stops.
 * The Gradle task then checks that the event was persisted.
 */
public final class ExternalPaperPlugin extends JavaPlugin {

    private static final NamespacedKey EVENT_TYPE =
        new NamespacedKey("fixture", "custom_event");
    private static final String BUILT_IN_LISTENER_PACKAGE =
        "net.okocraft.kansokusha.paper.builtin";
    private static final String INSPECTION_LISTENER_PACKAGE =
        "net.okocraft.kansokusha.paper.inspection";

    private static final Set<String> EXPECTED_BUILT_IN_LISTENERS = Set.of(
        "net.okocraft.kansokusha.paper.builtin.PaperBlockBreakListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockPlaceListener",
        "net.okocraft.kansokusha.paper.builtin.PaperSignChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBucketListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockHarvestListener",
        "net.okocraft.kansokusha.paper.builtin.PaperFlowerPotChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerItemAuditListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerContainerTransactionListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerWorkstationAuditListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerBlockInteractionListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockIgniteListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockBurnListener",
        "net.okocraft.kansokusha.paper.builtin.PaperTntPrimeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperExplosionBlockChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPistonMoveListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityBlockChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperNaturalBlockChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperFluidChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperSpongeAbsorbListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockFertilizeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperCauldronLevelChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperContainerTransferListener",
        "net.okocraft.kansokusha.paper.builtin.PaperContainerPickupListener",
        "net.okocraft.kansokusha.paper.builtin.PaperContainerProcessListener",
        "net.okocraft.kansokusha.paper.builtin.PaperDispenserAuditListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerJoinListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerQuitListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerKickListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerWorldChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerTeleportListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerGameModeChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerSpawnChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerDeathListener",
        "net.okocraft.kansokusha.paper.builtin.PaperChatListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerCommandListener",
        "net.okocraft.kansokusha.paper.builtin.PaperServerCommandListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityPlaceListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityBreakListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityLifecycleAuditListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityStateChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperGameRuleChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperWorldDifficultyChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperWorldBorderChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperWorldSpawnChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperWhitelistChangeListener"
    );

    private static final Set<String> EXPECTED_INSPECTION_LISTENERS = Set.of(
        "net.okocraft.kansokusha.paper.inspection.InspectionListener"
    );

    private Path resultFile;

    @Override
    public void onEnable() {
        this.resultFile = Path.of(System.getProperty("kansokusha.external-api-fixture.result"));

        try {
            var api = Kansokusha.api();
            api.registerEventType(PaperKansokusha.eventType(EVENT_TYPE, PayloadGeneration.FIRST));

            var submission = new EventSubmission(
                PaperKansokusha.key(EVENT_TYPE),
                PayloadGeneration.FIRST,
                Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MILLIS),
                api.localServerKey().orElseThrow(),
                null,
                null,
                null,
                null,
                EventPayload.copyOf(new byte[]{1, 2, 3})
            );
            check(api.submit(submission), "External event submission was not accepted.");
            check(
                EVENT_TYPE.equals(PaperKansokusha.namespacedKey(submission.eventType())),
                "NamespacedKey conversion was not lossless."
            );

            var registeredBuiltIns = registeredListenerClasses(BUILT_IN_LISTENER_PACKAGE);
            check(
                registeredBuiltIns.equals(EXPECTED_BUILT_IN_LISTENERS),
                "Unexpected Kansokusha built-in listener set: " + registeredBuiltIns
            );

            var registeredInspection = registeredListenerClasses(INSPECTION_LISTENER_PACKAGE);
            check(
                registeredInspection.equals(EXPECTED_INSPECTION_LISTENERS),
                "Unexpected Kansokusha inspection listener set: " + registeredInspection
            );

            this.verifyContainerRemoteSyncBoundary();

            // Plugins are disabled before shutdown hooks run.
            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> this.verifyAfterShutdown(api, submission), "kansokusha-external-api-fixture")
            );
        } catch (Throwable failure) {
            this.writeFailure(failure);
        }

        Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
    }

    private void verifyAfterShutdown(KansokushaApi api, EventSubmission submission) {
        try {
            if (Files.exists(this.resultFile)) {
                return;
            }
            try {
                Kansokusha.api();
                throw new AssertionError("Kansokusha.api() remained available after shutdown.");
            } catch (IllegalStateException expected) {
            }
            check(!api.submit(submission), "Previously acquired API accepted an event after shutdown.");

            Files.writeString(this.resultFile, "success");
        } catch (Throwable failure) {
            this.writeFailure(failure);
        }
    }

    private static void verifyContainerRemoteSyncBoundary() throws ReflectiveOperationException {
        var listenerClass = Class.forName(
            "net.okocraft.kansokusha.paper.builtin.PaperPlayerContainerTransactionListener"
        );
        var method = listenerClass.getDeclaredMethod("verifyRemoteSyncBoundaryForIntegration");
        check(method.trySetAccessible(), "Could not access container transaction verification hook.");
        method.invoke(null);
    }

    private static Set<String> registeredListenerClasses(String packageName) {
        var result = new HashSet<String>();
        for (var handlers : HandlerList.getHandlerLists()) {
            for (var registered : handlers.getRegisteredListeners()) {
                var listenerClass = registered.getListener().getClass();
                if (
                    registered.getPlugin().getName().equals("Kansokusha") &&
                        listenerClass.getPackageName().equals(packageName)
                ) {
                    result.add(listenerClass.getName());
                }
            }
        }
        return Set.copyOf(result);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void writeFailure(Throwable failure) {
        try {
            Files.createDirectories(this.resultFile.getParent());
            var stackTrace = new StringWriter();
            failure.printStackTrace(new PrintWriter(stackTrace));
            Files.writeString(this.resultFile, "failure\n" + stackTrace);
        } catch (Exception writeFailure) {
            failure.addSuppressed(writeFailure);
            failure.printStackTrace();
        }
    }
}
