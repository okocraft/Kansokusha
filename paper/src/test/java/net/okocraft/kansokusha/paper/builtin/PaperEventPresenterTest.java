package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.StoredPayload;
import net.okocraft.kansokusha.paper.command.KansokushaCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

class PaperEventPresenterTest {

    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private final EventRenderer renderer = PaperEventPresenter.renderer(Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);

    private org.mockito.MockedStatic<org.bukkit.Bukkit> itemFactory;

    @org.junit.jupiter.api.BeforeEach
    void installItemFactory() {
        this.itemFactory = PaperItemViewTestSupport.install();
    }

    @AfterEach
    void cleanup() {
        LanguageProvider.unload();
        this.itemFactory.close();
    }

    @Test
    void testNamesUseRegistryKeysAndSafeFallbacks() {
        var stone = PaperEventNames.name(Key.key("minecraft:stone"), EventRenderer.NameKind.BLOCK);
        Assertions.assertEquals("block.minecraft.stone", ((TranslatableComponent) stone).key());
        var diamond = PaperEventNames.name(Key.key("minecraft:diamond"), EventRenderer.NameKind.ITEM);
        Assertions.assertEquals("item.minecraft.diamond", ((TranslatableComponent) diamond).key());
        var creeper = PaperEventNames.name(Key.key("minecraft:creeper"), EventRenderer.NameKind.ENTITY);
        Assertions.assertEquals("entity.minecraft.creeper", ((TranslatableComponent) creeper).key());
        Assertions.assertEquals(Component.text("custom:stone"),
            PaperEventNames.name(Key.key("custom:stone"), EventRenderer.NameKind.BLOCK));
        Assertions.assertEquals(Component.text("minecraft:missing_block"),
            PaperEventNames.name(Key.key("minecraft:missing_block"), EventRenderer.NameKind.BLOCK));
    }

    @Test
    void testBreakDoesNotClaimSuccessAndInspectionOmitsLocation(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var event = event("block_break", new CompoundTag());
        var search = this.renderer.row(event, true, true);
        var inspection = this.renderer.row(event, true, false);
        var english = plain(search, Locale.ENGLISH);
        Assertions.assertTrue(english.contains("Alice break attempt"), english);
        Assertions.assertTrue(english.contains("(1,64,2)"), english);
        Assertions.assertFalse(plain(inspection, Locale.ENGLISH).contains("(1,64,2)"));
        Assertions.assertEquals(ClickEvent.runCommand("/kansokusha event " + event.eventId()), search.clickEvent());
        Assertions.assertNull(this.renderer.row(event, false, true).clickEvent());
        Assertions.assertTrue(plain(inspection, Locale.JAPANESE).contains("破壊操作"));
    }

    @Test
    void testBeforeAfterAndFullDetails(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var payload = new CompoundTag();
        var before = new CompoundTag();
        before.putString("Name", "minecraft:stone");
        var after = new CompoundTag();
        after.putString("Name", "minecraft:air");
        payload.put("pre_state", before);
        payload.put("post_state", after);
        payload.putString("source_event", "block_fade");
        var content = new PaperEventPresenter().present(event("natural_block_change", payload), true, this.renderer).orElseThrow();
        var details = content.details().stream().map(line -> plain(line, Locale.ENGLISH)).toList();
        Assertions.assertTrue(details.stream().anyMatch(line -> line.contains("Before") && line.contains("Stone")), details.toString());
        Assertions.assertTrue(details.stream().anyMatch(line -> line.contains("After") && line.contains("Air")), details.toString());
        Assertions.assertFalse(plain(content.summary(), Locale.ENGLISH).contains("block_fade"));
        Assertions.assertTrue(plain(content.hover(), Locale.ENGLISH).contains("block_fade"));
    }

    @Test
    void testForeignUnknownGenerationAndMalformedPayloadDoNotDecode() {
        var valid = event("block_break", new CompoundTag());
        var presenter = new PaperEventPresenter();
        for (var type : new String[]{"custom:block_break", "kansokusha:unknown"}) {
            var event = new SearchPage.Event(valid.eventId(), Key.key(type), NOW, valid.server(), valid.world(),
                valid.position(), valid.actor(), valid.actorName(), valid.targetType(), valid.searchText(), valid.payload());
            Assertions.assertTrue(presenter.present(event, true, this.renderer).isEmpty());
        }
        for (var payload : new StoredPayload[]{
            new StoredPayload(new PayloadGeneration(2), EventPayload.copyOf(new byte[]{-1})),
            new StoredPayload(PayloadGeneration.FIRST, EventPayload.copyOf(new byte[]{-1}))}) {
            var event = new SearchPage.Event(valid.eventId(), valid.eventType(), NOW, valid.server(), valid.world(),
                valid.position(), valid.actor(), valid.actorName(), valid.targetType(), valid.searchText(), Optional.of(payload));
            Assertions.assertTrue(presenter.present(event, true, this.renderer).isEmpty());
        }
    }

    @Test
    void testCommunicationPreviewAndFullOriginalText(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var payload = new CompoundTag();
        var command = "<red>/say " + "a".repeat(200) + " END";
        payload.putString("command", command);
        var content = new PaperEventPresenter().present(event("paper_player_command", payload), true, this.renderer).orElseThrow();
        Assertions.assertTrue(plain(content.summary(), Locale.ENGLISH).contains("command input"));
        Assertions.assertFalse(plain(content.summary(), Locale.ENGLISH).contains(" END"));
        Assertions.assertEquals(command, content.sections().get("text_command").lines().get().stream()
            .map(line -> plain(line, Locale.ENGLISH)).collect(java.util.stream.Collectors.joining()));
        payload = new CompoundTag();
        payload.putString("from_world", "minecraft:the_nether");
        content = new PaperEventPresenter().present(event("player_world_change", payload), true, this.renderer).orElseThrow();
        var summary = content.details().stream().map(line -> plain(line, Locale.ENGLISH)).collect(java.util.stream.Collectors.joining());
        Assertions.assertTrue(summary.contains("minecraft:the_nether"), summary);
        Assertions.assertTrue(summary.contains("minecraft:overworld"), summary);
    }

    @Test
    void testLargePayloadStillHasAUsefulSentence(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var original = event("block_break", new CompoundTag());
        var event = new SearchPage.Event(original.eventId(), original.eventType(), original.occurredAt(),
            original.server(), original.world(), original.position(), original.actor(), original.actorName(),
            original.targetType(), original.searchText());
        var text = plain(this.renderer.row(event, true, false), Locale.ENGLISH);
        Assertions.assertTrue(text.contains("Alice break attempt"), text);
        Assertions.assertFalse(text.contains("payload omitted"), text);
    }

    @Test
    void testCurrentMinecraftBlockStateNeverLeaksIntoRows(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var payload = new CompoundTag();
        payload.put("pre_state", net.minecraft.nbt.NbtUtils.writeBlockState(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
        payload.put("post_state", net.minecraft.nbt.NbtUtils.writeBlockState(net.minecraft.world.level.block.Blocks.SMALL_AMETHYST_BUD.defaultBlockState()));
        payload.putString("source_event", "block_spread");
        var content = new PaperEventPresenter().present(event("natural_block_change", payload), true, this.renderer).orElseThrow();
        var row = plain(content.summary(), Locale.JAPANESE);
        Assertions.assertFalse(row.contains("properties"), row);
        Assertions.assertFalse(row.contains("block_spread"), row);
        Assertions.assertFalse(row.contains("変更前"), row);
        var overview = content.details().stream().map(line -> plain(line, Locale.ENGLISH)).collect(java.util.stream.Collectors.joining());
        Assertions.assertFalse(overview.contains("{id:"), overview);
        Assertions.assertFalse(overview.contains("facing"), overview);
        Assertions.assertTrue(overview.contains("Small Amethyst Bud"), overview);
        Assertions.assertTrue(content.sections().containsKey("raw"));
    }

    static SearchPage.Event event(String type, CompoundTag payload) {
        return new SearchPage.Event(UUID.randomUUID(), Key.key("kansokusha", type), NOW, Optional.empty(),
            Optional.of(Key.key("minecraft:overworld")), Optional.of(new BlockPosition(1, 64, 2)),
            Optional.of(new PlayerActor(UUID.randomUUID())), Optional.of("Alice"), Optional.of(Key.key("minecraft:stone")),
            Optional.empty(), Optional.of(new StoredPayload(PayloadGeneration.FIRST, PaperPayloadNbtCodec.encode(payload))));
    }

    static String plain(Component value, Locale locale) {
        return PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(value, locale));
    }
}
