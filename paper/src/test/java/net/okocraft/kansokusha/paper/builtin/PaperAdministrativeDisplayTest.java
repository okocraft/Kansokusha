package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import net.okocraft.kansokusha.paper.command.KansokushaCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;

class PaperAdministrativeDisplayTest {

    @AfterEach
    void cleanup() {
        LanguageProvider.unload();
    }

    @Test
    void testBorderRequestDoesNotClaimCompletionAndDifficultyHasBeforeAfter(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var renderer = PaperEventPresenter.renderer(Clock.systemUTC(), ZoneOffset.UTC);
        var payload = new CompoundTag();
        payload.putString("before", "normal");
        payload.putString("after", "hard");
        var summary = new PaperEventPresenter().present(PaperEventPresenterTest.event("world_difficulty_change", payload),
            false, renderer).orElseThrow().summary();
        var text = PaperEventPresenterTest.plain(summary, Locale.ENGLISH);
        Assertions.assertTrue(text.contains("Before: normal"), text);
        Assertions.assertTrue(text.contains("After: hard"), text);
        payload = new CompoundTag();
        payload.putDouble("before_size", 1000);
        payload.putDouble("after_size", 2000);
        payload.putLong("transition_duration_ticks", 400);
        var content = new PaperEventPresenter().present(PaperEventPresenterTest.event("world_border_change", payload),
            true, renderer).orElseThrow();
        Assertions.assertTrue(PaperEventPresenterTest.plain(content.summary(), Locale.ENGLISH).contains("requested"));
        Assertions.assertTrue(content.details().stream().map(line -> PaperEventPresenterTest.plain(line, Locale.ENGLISH))
            .anyMatch(line -> line.contains("400")));
    }
}
