package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class PaperBuiltInListenerCollectionTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");

    private static final List<String> EXPECTED_LISTENER_ORDER = List.of(
        "PaperBlockBreakListener",
        "PaperBlockPlaceListener",
        "PaperSignChangeListener",
        "PaperBucketListener",
        "PaperBlockHarvestListener",
        "PaperFlowerPotChangeListener",
        "PaperBlockIgniteListener",
        "PaperBlockBurnListener",
        "PaperTntPrimeListener",
        "PaperExplosionBlockChangeListener",
        "PaperPistonMoveListener",
        "PaperEntityBlockChangeListener",
        "PaperNaturalBlockChangeListener",
        "PaperFluidChangeListener",
        "PaperSpongeAbsorbListener",
        "PaperBlockFertilizeListener",
        "PaperCauldronLevelChangeListener",
        "PaperContainerTransferListener",
        "PaperContainerPickupListener",
        "PaperContainerProcessListener",
        "PaperPlayerJoinListener",
        "PaperPlayerQuitListener",
        "PaperPlayerKickListener",
        "PaperPlayerWorldChangeListener",
        "PaperPlayerTeleportListener",
        "PaperPlayerGameModeChangeListener",
        "PaperPlayerSpawnChangeListener",
        "PaperPlayerDeathListener",
        "PaperChatListener",
        "PaperPlayerCommandListener",
        "PaperServerCommandListener",
        "PaperEntityPlaceListener",
        "PaperEntityBreakListener",
        "PaperEntityStateChangeListener",
        "PaperGameRuleChangeListener",
        "PaperWorldDifficultyChangeListener",
        "PaperWorldBorderChangeListener",
        "PaperWorldSpawnChangeListener",
        "PaperWhitelistChangeListener"
    );

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    @Test
    void testRegistersEveryAdoptedListenerExactlyOnceInContractOrder() {
        var api = registeringApi();
        var registrar = new RecordingRegistrar();

        var collection = PaperBuiltInListenerCollection.register(api, SERVER_KEY, registrar);

        Assertions.assertEquals(EXPECTED_LISTENER_ORDER, registrar.registeredNames());
        Assertions.assertFalse(
            registrar.registeredNames().contains("PaperPlayerItemAuditListener"),
            "The delegated item-audit listener must not be registered separately."
        );

        collection.close();

        var reverseOrder = new ArrayList<>(EXPECTED_LISTENER_ORDER);
        Collections.reverse(reverseOrder);
        Assertions.assertEquals(reverseOrder, registrar.unregisteredNames());
    }

    @Test
    void testPartialRegistrationFailureRollsBackPreviouslyRegisteredListeners() {
        var api = Mockito.mock(KansokushaApi.class);
        var registrations = new AtomicInteger();
        Mockito.when(api.registerEventType(Mockito.any())).thenAnswer(ignored ->
            registrations.incrementAndGet() == 3
                ? RegistrationOutcome.CONFLICT
                : RegistrationOutcome.REGISTERED
        );
        var registrar = new RecordingRegistrar();

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> PaperBuiltInListenerCollection.register(api, SERVER_KEY, registrar)
        );

        Assertions.assertEquals(
            List.of("PaperBlockBreakListener", "PaperBlockPlaceListener"),
            registrar.registeredNames()
        );
        Assertions.assertEquals(
            List.of("PaperBlockPlaceListener", "PaperBlockBreakListener"),
            registrar.unregisteredNames()
        );
    }

    @Test
    void testCloseUnregistersAllListenersBeforeClearingInFlightState() {
        var api = registeringApi();
        var registrar = new RecordingRegistrar();
        var collection = PaperBuiltInListenerCollection.register(api, SERVER_KEY, registrar);
        var listener = registrar.registered.stream()
            .filter(PaperBlockBreakListener.class::isInstance)
            .map(PaperBlockBreakListener.class::cast)
            .findFirst()
            .orElseThrow();
        var event = blockBreakEvent();

        listener.capture(event);
        Assertions.assertEquals(1, listener.inFlightCount());

        collection.close();

        Assertions.assertEquals(1, registrar.blockBreakInFlightAtUnregister);
        Assertions.assertEquals(0, listener.inFlightCount());

        listener.finalizeEvent(event);
        Mockito.verify(api, Mockito.never()).submit(Mockito.any());

        var unregisterCount = registrar.unregistered.size();
        collection.close();
        Assertions.assertEquals(unregisterCount, registrar.unregistered.size());
    }

    private static KansokushaApi registeringApi() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any())).thenReturn(RegistrationOutcome.REGISTERED);
        return api;
    }

    private static BlockBreakEvent blockBreakEvent() {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));

        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(12);
        Mockito.when(block.getY()).thenReturn(64);
        Mockito.when(block.getZ()).thenReturn(-7);
        Mockito.when(block.getBlockData()).thenReturn(Blocks.STONE.defaultBlockState().asBlockData());

        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId())
            .thenReturn(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        return new BlockBreakEvent(block, player);
    }

    private static final class RecordingRegistrar
        implements PaperBuiltInListenerCollection.Registrar {

        private final List<Listener> registered = new ArrayList<>();
        private final List<Listener> unregistered = new ArrayList<>();
        private int blockBreakInFlightAtUnregister = -1;

        @Override
        public void register(Listener listener) {
            this.registered.add(listener);
        }

        @Override
        public void unregister(Listener listener) {
            if (listener instanceof PaperBlockBreakListener blockBreakListener) {
                this.blockBreakInFlightAtUnregister = blockBreakListener.inFlightCount();
            }
            this.unregistered.add(listener);
        }

        private List<String> registeredNames() {
            return names(this.registered);
        }

        private List<String> unregisteredNames() {
            return names(this.unregistered);
        }

        private static List<String> names(List<Listener> listeners) {
            return listeners.stream()
                .map(listener -> listener.getClass().getSimpleName())
                .toList();
        }
    }
}
