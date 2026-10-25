package net.okocraft.kansokusha.paper.builtin;

import net.okocraft.kansokusha.common.command.EventRenderer.NameKind;
import net.okocraft.kansokusha.common.command.EventView;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

@NotNullByDefault
record PaperEventView(String type, EventView sentence, NameKind targetKind, List<String> fields) {

    static PaperEventView of(String type, String sentence, NameKind targetKind, String... fields) {
        return new PaperEventView(type, new EventView(type, sentence), targetKind, List.of(fields));
    }
}
