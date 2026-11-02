package net.okocraft.kansokusha.testmod;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Server fixture: committed block break is searchable after flush; a canceled break is absent. */
@Mod("kansokusha_test")
public final class NativeContractFixture {
    private static final Key BREAK = Key.key("kansokusha:block_break");
    private static final Key TRANSACTION = Key.key("kansokusha:container_transaction");
    private static final Key CHAT = Key.key("kansokusha:neoforge_chat");
    private static final Key COMMAND = Key.key("kansokusha:neoforge_player_command");
    private static final Key BORDER = Key.key("kansokusha:world_border_change");
    private static final Key PROCESS = Key.key("kansokusha:container_process");
    private static final Key TELEPORT = Key.key("kansokusha:player_teleport");
    private static final Key TRANSFER = Key.key("kansokusha:container_transfer");
    private static final Key SERVER_COMMAND = Key.key("kansokusha:neoforge_server_command");
    private MinecraftServer server;
    private BlockPos committed;
    private BlockPos cancelled;
    private UUID actor;
    private UUID containerActor;
    private int ticks;

    public NativeContractFixture() {
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void start(ServerStartedEvent event) {
        this.server = event.getServer();
        var level = server.overworld();
        committed = new BlockPos(208, 80, 208);
        cancelled = new BlockPos(209, 80, 208);
        level.getChunkAt(committed);
        level.setBlock(committed, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(cancelled, Blocks.STONE.defaultBlockState(), 3);
        actor = UUID.fromString("7b7ac3d0-f1c8-46da-89a7-f5c2a46ee198");
        var player = new ServerPlayer(server, level, new GameProfile(actor, "KansokushaProbe"), ClientInformation.createDefault());
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
        NeoForge.EVENT_BUS.post(new BreakBlockEvent(level, committed, level.getBlockState(committed), player));
        level.setBlock(committed, Blocks.AIR.defaultBlockState(), 3);
        var denied = new BreakBlockEvent(level, cancelled, level.getBlockState(cancelled), player);
        denied.setCanceled(true);
        NeoForge.EVENT_BUS.post(denied);
        var chat = new ServerChatEvent(player, "Original fixture chat", net.minecraft.network.chat.Component.literal("changed"));
        chat.setCanceled(true);
        NeoForge.EVENT_BUS.post(chat);
        var command = new CommandEvent(server.getCommands().getDispatcher().parse(
            "say OriginalFixtureCommand", player.createCommandSourceStack()));
        command.setCanceled(true);
        NeoForge.EVENT_BUS.post(command);
        var consoleCommand = new CommandEvent(server.getCommands().getDispatcher().parse(
            "list", server.createCommandSourceStack()));
        consoleCommand.setCanceled(true);
        NeoForge.EVENT_BUS.post(consoleCommand);

        if (server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated) {
            var previous = dedicated.isUsingWhitelist();
            dedicated.setUsingWhitelist(!previous);
            dedicated.setUsingWhitelist(previous);
            var whitelisted = new net.minecraft.server.players.NameAndId(
                UUID.fromString("5874fe4e-e58d-4a38-bad6-9bbc0a81af42"), "ListProbe");
            var whitelist = server.getPlayerList().getWhiteList();
            if (!whitelist.add(new net.minecraft.server.players.UserWhiteListEntry(whitelisted))
                || !whitelist.remove(whitelisted))
                throw new AssertionError("Native whitelist fixture did not change its own profile");
        }

        var chestPos = new BlockPos(210, 80, 208);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.STONE, 10));
        containerActor = UUID.fromString("31d4271c-d154-48eb-a11c-c889f2aa949a");
        var containerPlayer = new PacketProbePlayer(level, new GameProfile(containerActor, "KansokushaChest"));
        containerPlayer.setPos(210.5, 80, 208.5);
        var menu = ChestMenu.threeRows(1, containerPlayer.getInventory(), chest);
        containerPlayer.containerMenu = menu;
        NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(containerPlayer, menu));
        containerPlayer.connection.handleContainerClick(new ServerboundContainerClickPacket(
            1, menu.getStateId(), (short) 0, (byte) 0, ContainerInput.PICKUP,
            new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
        if (!chest.getItem(0).isEmpty() || menu.getCarried().getCount() != 10)
            throw new AssertionError("Native chest click did not commit");
        containerPlayer.teleportTo(211.5, 80, 208.5);
        var border = level.getWorldBorder();
        border.setCenter(border.getCenterX() + 1, border.getCenterZ() + 1);
        border.setSize(border.getSize() - 1);
        var furnacePos = new BlockPos(212, 80, 208);
        level.setChunkForced(furnacePos.getX() >> 4, furnacePos.getZ() >> 4, true);
        level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
        var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnacePos);
        furnace.setItem(0, new ItemStack(Items.IRON_ORE, 1));
        furnace.setItem(1, new ItemStack(Items.COAL, 1));
        var hopperPos = new BlockPos(214, 80, 208);
        level.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState(), 3);
        var supplyPos = hopperPos.above();
        level.setBlock(supplyPos, Blocks.CHEST.defaultBlockState(), 3);
        ((ChestBlockEntity) level.getBlockEntity(supplyPos)).setItem(0, new ItemStack(Items.APPLE, 1));
        NativeUiFixture.verify(server);
        NativeEntityFixture.start(server);
        NativeMobBlockMutationFixture.start(server);
        NativeBlockFixture.start(server);
        NativeContainerMoveFixture.start(server);
    }

    private void tick(ServerTickEvent.Post event) {
        if (server == null) return;
        ++ticks;
        NativeBlockFixture.advance(server, ticks);
        if (ticks == 1300) {
            try {
                NativeBlockFixture.verifyPiston(EventSearchBackend.require(Kansokusha.api()));
                verifyHopper(EventSearchBackend.require(Kansokusha.api()));
                NativeContainerMoveFixture.verifyStored(EventSearchBackend.require(Kansokusha.api()));
                Files.writeString(Path.of(System.getProperty("kansokusha.fixture.result") + ".piston"), "success");
            } catch (Throwable failure) {
                try { Files.writeString(Path.of(System.getProperty("kansokusha.fixture.result") + ".piston"), failure.toString()); }
                catch (Exception ignored) { }
                throw new AssertionError("Kansokusha piston contract fixture failed", failure);
            }
        }
        if (ticks != 250) return;
        try {
            var backend = EventSearchBackend.require(Kansokusha.api());
            var request = new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
                SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
                new SearchRequest.Constraints(Set.of(BREAK)), Optional.empty(), 100);
            var page = backend.search(request).join();
            var fixtureRecords = page.events().stream().filter(row -> row.actor()
                .filter(value -> value instanceof net.okocraft.kansokusha.api.actor.PlayerActor player
                    && player.uniqueId().equals(actor)).isPresent()).toList();
            if (fixtureRecords.size() != 1) throw new AssertionError("Expected one committed break, found " + fixtureRecords.size());
            var record = fixtureRecords.getFirst();
            if (!record.position().equals(Optional.of(new BlockPosition(208, 80, 208))))
                throw new AssertionError("Committed position differs");
            if (!record.world().equals(Optional.of(Key.key("minecraft:overworld"))))
                throw new AssertionError("Committed world differs");
            if (!record.targetType().equals(Optional.of(Key.key("minecraft:stone"))))
                throw new AssertionError("Committed target differs");
            if (!record.actorName().equals(Optional.of("KansokushaProbe")))
                throw new AssertionError("Player name cache was not updated by login");
            if (record.payload().orElseThrow().generation().value() != 2)
                throw new AssertionError("Native payload generation differs");
            if (fixtureRecords.stream().anyMatch(row -> row.position()
                .equals(Optional.of(new BlockPosition(209, 80, 208))))) throw new AssertionError("Canceled break was recorded");
            var transactionPage = backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
                SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
                new SearchRequest.Constraints(Set.of(TRANSACTION)), Optional.empty(), 100)).join();
            var transactions = transactionPage.events().stream().filter(row -> row.actor()
                .filter(value -> value instanceof net.okocraft.kansokusha.api.actor.PlayerActor player
                    && player.uniqueId().equals(containerActor)).isPresent()).toList();
            if (transactions.size() != 1 || !transactions.getFirst().position()
                .equals(Optional.of(new BlockPosition(210, 80, 208)))
                || !transactions.getFirst().targetType().equals(Optional.of(Key.key("minecraft:stone"))))
                throw new AssertionError("Committed chest transfer was not recorded at its origin");
            assertInput(backend, CHAT, "Original fixture chat");
            assertInput(backend, COMMAND, "say OriginalFixtureCommand");
            var consolePage = backend.search(new SearchRequest(SearchQueryParser.parse("filter list",
                java.time.Clock.systemUTC(), java.time.ZoneOffset.UTC, null),
                new SearchRequest.Constraints(Set.of(SERVER_COMMAND)), Optional.empty(), 100)).join();
            if (consolePage.events().size() != 1 || consolePage.events().getFirst().actor().isPresent())
                throw new AssertionError("Canceled original console command was not audited");
            var borderPage = backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
                SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
                new SearchRequest.Constraints(Set.of(BORDER)), Optional.empty(), 100)).join();
            if (borderPage.events().size() != 2 || borderPage.events().stream().anyMatch(row ->
                !row.world().equals(Optional.of(Key.key("minecraft:overworld")))))
                throw new AssertionError("Committed world border center and size were not recorded");
            var processPage = backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
                SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
                new SearchRequest.Constraints(Set.of(PROCESS)), Optional.empty(), 100)).join();
            if (processPage.events().size() != 1 || !processPage.events().getFirst().position()
                .equals(Optional.of(new BlockPosition(212, 80, 208)))
                || !processPage.events().getFirst().targetType().equals(Optional.of(Key.key("minecraft:iron_ingot"))))
                throw new AssertionError("Committed furnace output was not recorded");
            var teleportPage = backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
                SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
                new SearchRequest.Constraints(Set.of(TELEPORT)), Optional.empty(), 100)).join();
            if (teleportPage.events().stream().noneMatch(row -> row.actor()
                .filter(value -> value instanceof net.okocraft.kansokusha.api.actor.PlayerActor player
                    && player.uniqueId().equals(containerActor)).isPresent()
                && row.position().equals(Optional.of(new BlockPosition(211, 80, 208)))))
                throw new AssertionError("Direct ServerPlayer teleport was not recorded");
            NativeEntityFixture.verify(backend);
            NativeMobBlockMutationFixture.verify(backend);
            NativeBlockFixture.verify(backend);
            NativeContainerMoveFixture.verifyMovement();
            var whitelistPage = backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
                SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
                new SearchRequest.Constraints(Set.of(Key.key("kansokusha:whitelist_change"))),
                Optional.empty(), 20)).join();
            if (whitelistPage.events().size() != 4)
                throw new AssertionError("Whitelist toggle and profile changes were not saved: "
                    + whitelistPage.events().size());
            Files.writeString(Path.of(System.getProperty("kansokusha.fixture.result")), "success");
        } catch (Throwable failure) {
            try { Files.writeString(Path.of(System.getProperty("kansokusha.fixture.result")), failure.toString()); }
            catch (Exception ignored) { }
            throw new AssertionError("Kansokusha native contract fixture failed", failure);
        }
    }

    private void assertInput(EventSearchBackend backend, Key eventType, String rawText) {
        var query = SearchQueryParser.parse("filter \"" + rawText + "\"",
            java.time.Clock.systemUTC(), java.time.ZoneOffset.UTC, null);
        var page = backend.search(new SearchRequest(query,
            new SearchRequest.Constraints(Set.of(eventType)), Optional.empty(), 100)).join();
        var records = page.events().stream().filter(row -> row.actor()
            .filter(value -> value instanceof net.okocraft.kansokusha.api.actor.PlayerActor player
                && player.uniqueId().equals(actor)).isPresent()).toList();
        if (records.size() != 1 || !new String(records.getFirst().payload().orElseThrow().bytes().copyBytes(),
            java.nio.charset.StandardCharsets.UTF_8).contains(rawText))
            throw new AssertionError("Canceled input was not audited: " + eventType);
    }

    private static void verifyHopper(EventSearchBackend backend) {
        var transferPage = backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
            SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
            new SearchRequest.Constraints(Set.of(TRANSFER)), Optional.empty(), 100)).join();
        var transfer = transferPage.events().stream().filter(row -> row.position()
            .equals(Optional.of(new BlockPosition(214, 80, 208)))
            && row.targetType().equals(Optional.of(Key.key("minecraft:apple")))).findFirst()
            .orElseThrow(() -> new AssertionError("Vanilla hopper transfer was not recorded"));
        var payload = com.google.gson.JsonParser.parseString(new String(
            transfer.payload().orElseThrow().bytes().copyBytes(), java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
        if (!payload.get("from").getAsString().equals("214, 81, 208")
            || !payload.get("to").getAsString().equals("214, 80, 208")
            || !payload.get("initiator_role").getAsString().equals("destination")
            || payload.get("attempt_count").getAsInt() != 1
            || payload.get("item_amount").getAsLong() != 1)
            throw new AssertionError("Committed chest-to-hopper route and count differ: " + payload);
    }
}
