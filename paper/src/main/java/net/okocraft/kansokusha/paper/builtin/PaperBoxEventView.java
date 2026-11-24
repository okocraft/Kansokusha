package net.okocraft.kansokusha.paper.builtin;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import dev.siroshun.mcmsgdef.Placeholder;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.common.command.EventPresentationMessages;
import net.okocraft.kansokusha.common.command.EventPresenter;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.search.SearchPage;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Map;

@NotNullByDefault
final class PaperBoxEventView {

    static final Key STOCK_CHANGE = Key.key("box", "stock_change");
    static final String AUTOSTORE = "box:autostore_item";
    static final String STICK = "box:stick_container_change";

    private static final DefaultMessageDefiner MESSAGES = DefaultMessageDefiner.create();
    private static final Placeholder<Component> ACTOR = value -> Argument.component("actor", value);
    private static final Placeholder<Component> ITEM = value -> Argument.component("item", value);
    private static final Placeholder<Component> CONTAINER = value -> Argument.component("container", value);
    private static final Placeholder<Component> REASON = value -> Argument.component("reason", value);
    private static final Placeholder<String> AMOUNT = value -> Argument.string("amount", value);
    private static final Placeholder<String> STOCK = value -> Argument.string("stock", value);
    private static final Placeholder<Integer> BEFORE = value -> Argument.numeric("before", value);
    private static final Placeholder<Integer> AFTER = value -> Argument.numeric("after", value);

    private static final MessageKey.Arg3<Component, Component, String> STOCK_SUMMARY = MESSAGES.define(
        "kansokusha.display.box.stock", "<actor>Box stock <item> <amount>").with(ACTOR, ITEM, AMOUNT);
    private static final MessageKey.Arg3<Component, Component, String> PICKUP_SUMMARY = MESSAGES.define(
        "kansokusha.display.box.autostore", "<actor>AutoStore <item> ×<amount>").with(ACTOR, ITEM, AMOUNT);
    private static final MessageKey.Arg4<Component, Component, Component, String> STICK_SUMMARY = MESSAGES.define(
        "kansokusha.display.box.stick", "<actor>Box Stick <container> <item> <amount>").with(ACTOR, CONTAINER, ITEM, AMOUNT);
    private static final MessageKey.Arg1<String> STOCK_NAME = MESSAGES.define(
        "kansokusha.display.box.stock-name", "Stock: <stock>").with(STOCK);
    private static final MessageKey.Arg2<Integer, Integer> QUANTITY = MESSAGES.define(
        "kansokusha.display.box.quantity", "Quantity: <before> → <after>").with(BEFORE, AFTER);
    private static final MessageKey.Arg1<String> DELTA = MESSAGES.define(
        "kansokusha.display.box.delta", "Change: <amount>").with(AMOUNT);
    private static final MessageKey.Arg1<Component> CAUSE = MESSAGES.define(
        "kansokusha.display.box.cause", "Reason: <reason>").with(REASON);
    private static final MessageKey CONSOLE = MESSAGES.define("kansokusha.display.box.console", "Console");
    private static final MessageKey UNKNOWN_CONTAINER = MESSAGES.define("kansokusha.display.box.unknown-container", "Container");
    private static final Map<String, MessageKey> REASONS = Map.ofEntries(
        reason("admin_reset_all", "Full reset"),
        reason("admin_reset", "Admin reset"),
        reason("admin_give", "Admin deposit"),
        reason("admin_take", "Admin withdrawal"),
        reason("admin_set", "Admin quantity set"),
        reason("api", "API call"),
        reason("autostore", "AutoStore"),
        reason("craft", "Crafting"),
        reason("overflow", "Overflow"),
        reason("deposit", "Deposit"),
        reason("withdraw", "Withdrawal"),
        reason("give", "Gift sent"),
        reason("receive", "Gift received"),
        reason("stick_block_item", "Box Stick block selection"),
        reason("stick_furnace", "Box Stick furnace operation"),
        reason("stick_brewer", "Box Stick brewing stand operation"),
        reason("stick_container", "Box Stick container operation"),
        reason("stick_block_place", "Box Stick block placement"),
        reason("stick_item_consume", "Box Stick item consumption"),
        reason("stick_item_break", "Box Stick item break"),
        reason("stick_projectile_launch", "Box Stick projectile launch"),
        reason("stick_resurrect", "Box Stick resurrection"),
        reason("stick_shoot_bow", "Box Stick bow shot")
    );

    private PaperBoxEventView() {
    }

    private static Map.Entry<String, MessageKey> reason(String name, String english) {
        return Map.entry(name, MESSAGES.define("kansokusha.display.box.reason." + name, english));
    }

    static Map<String, String> defaultMessages() {
        return MESSAGES.getCollectedMessages();
    }

    static EventPresenter.Content stock(SearchPage.Event event, CompoundTag payload, boolean detailed, EventRenderer renderer) {
        var item = event.targetType().map(key -> renderer.name(key, EventRenderer.NameKind.ITEM))
            .orElseGet(() -> Component.text(payload.getStringOr("item_name", "#" + payload.getIntOr("item_id", 0))));
        var delta = signed(payload.getLongOr("amount_delta", 0));
        var name = payload.getStringOr("actor_name", "");
        var actor = payload.getStringOr("actor_kind", "").equals("console") ? CONSOLE.asComponent()
            : event.actorName().isPresent() || name.isEmpty() ? renderer.actor(event) : Component.text(name);
        var cause = payload.getStringOr("cause", "");
        var translated = REASONS.get(cause);
        var details = List.<Component>of(
            STOCK_NAME.apply(payload.getStringOr("stock_name", "")),
            QUANTITY.apply(payload.getIntOr("before", 0), payload.getIntOr("after", 0)),
            DELTA.apply(delta),
            CAUSE.apply(translated == null ? Component.text(cause) : translated.asComponent())
        );
        return new EventPresenter.Content(STOCK_SUMMARY.apply(spaced(actor), item, delta),
            detailed ? details : List.of(), EventRenderer.join(details),
            Map.of("raw", new EventPresenter.Section(EventPresentationMessages.RAW.asComponent(),
                () -> PaperPayloadDisplay.details(payload))));
    }

    static Component migratedSummary(SearchPage.Event event, CompoundTag payload, EventRenderer renderer, Component item) {
        var actor = spaced(renderer.actor(event));
        var source = payload.getStringOr("source_event", "");
        if (source.equals(AUTOSTORE) && event.eventType().value().equals("item_pickup")) {
            var stack = PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("stack"));
            return PICKUP_SUMMARY.apply(actor, item, Integer.toString(stack.getAmount()));
        }
        if (source.equals(STICK) && event.eventType().value().equals("container_transaction")) {
            var type = payload.getCompoundOrEmpty("container").getStringOr("holder_block_type", "");
            var container = type.isEmpty() ? UNKNOWN_CONTAINER.asComponent()
                : renderer.name(Key.key(type), EventRenderer.NameKind.BLOCK);
            return STICK_SUMMARY.apply(actor, container, item, signed(payload.getIntOr("amount_delta", 0)));
        }
        return Component.empty();
    }

    private static Component spaced(Component actor) {
        return actor.equals(Component.empty()) ? actor : actor.append(Component.space());
    }

    private static String signed(long amount) {
        return amount >= 0 ? "+" + amount : "−" + Long.toString(amount).substring(1);
    }
}
