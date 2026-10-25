package net.okocraft.kansokusha.paper.builtin;

import net.okocraft.kansokusha.common.command.EventRenderer.NameKind;
import net.okocraft.kansokusha.common.command.EventView;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

@NotNullByDefault
record PaperEventView(String type, EventView sentence, NameKind targetKind, String quantity, List<String> primary, List<String> fields) {

    static PaperEventView of(String type, String sentence, NameKind targetKind, String quantity, String primary, String hover) {
        return new PaperEventView(type, new EventView(type, sentence), targetKind, quantity, words(primary), words(hover));
    }

    private static List<String> words(String text) {
        return text.isEmpty() ? List.of() : List.of(text.split(" "));
    }
}
