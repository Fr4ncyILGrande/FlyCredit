package it.flycredit;

import it.flycredit.core.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import static net.kyori.adventure.text.format.NamedTextColor.*;

/** Renders localized chat using literal Adventure components. Dynamic values are never parsed as markup. */
final class Chat {
    private static final Pattern SLOT = Pattern.compile("%([1-9][0-9]*)\\$s");
    private static final Component CHEVRON = Component.text(" » ", DARK_GRAY);
    private static final Component SEPARATOR = Component.text("  ·  ", DARK_GRAY);
    private static final String ROOT = "/flycredit";

    private Chat() {}

    private static Language languageOf(CommandSender sender) {
        return sender instanceof Player player ? Language.forLocale(player.locale()) : Language.ENGLISH;
    }

    private static Component format(Language language, String key, NamedTextColor color, Object... values) {
        Component template = Component.text(language.text(key), color);
        if (values.length == 0) return template;
        return template.replaceText(replacement -> replacement.match(SLOT).replacement((match, builder) -> {
            Object value = values[Integer.parseInt(match.group(1)) - 1];
            if (value instanceof Component component) return component;
            if (value.equals("/flycredit help") || value.equals("/flycredit off")) {
                return Component.text(value.toString(), AQUA).clickEvent(ClickEvent.suggestCommand(value.toString()));
            }
            return Component.text(value.toString(), GOLD);
        }));
    }

    static void notice(CommandSender sender, String key, Object... values) {
        Language language = languageOf(sender);
        Component body;
        if (key.startsWith("usage.")) {
            body = format(language, "command.usage", YELLOW, Component.text(language.text(key), AQUA));
        } else {
            body = format(language, key, tone(key), values);
        }
        sender.sendMessage(Component.text("FlyCredit", AQUA).append(CHEVRON, body));
    }

    private static NamedTextColor tone(String key) {
        return switch (key) {
            case "flight.started", "flight.stopped", "transfer.sent", "transfer.received",
                 "account.balanceChanged", "account.yourBalance", "diagnostics.enabled", "diagnostics.canReceive" -> GREEN;
            case "command.cooldown", "flight.busy", "flight.finishFirst", "flight.inactive", "flight.startPending",
                 "flight.startCancelled", "flight.exhausted", "flight.accessLost", "flight.leftClaim",
                 "flight.gameModeChanged", "flight.alreadyFlying", "transfer.pending", "transfer.disabled",
                 "error.busy", "diagnostics.disabled", "diagnostics.transfersOff", "diagnostics.cannotReceive" -> YELLOW;
            case "diagnostics.server", "diagnostics.ownersOnly", "diagnostics.guestTypes", "diagnostics.groups" -> GRAY;
            default -> RED;
        };
    }

    /** The entire panel is one chat message, with a single heading. */
    static final class Panel {
        private final CommandSender sender;
        private final Language language;
        private final List<Component> lines = new ArrayList<>();

        Panel(CommandSender sender, String heading, String titleKey, Object... values) {
            this.sender = sender;
            language = languageOf(sender);
            lines.add(Component.empty().append(Component.text(heading, AQUA, TextDecoration.BOLD),
                    CHEVRON, format(language, titleKey, WHITE, values)));
        }

        void command(String usageKey, String descriptionKey) {
            String full = language.text(usageKey);
            String tail = full.substring(ROOT.length() + 1);
            int argument = full.indexOf('<');
            if (argument < 0) argument = full.indexOf('[');
            String suggestion = argument < 0 ? full : full.substring(0, argument);
            lines.add(Component.text(tail, AQUA).clickEvent(ClickEvent.suggestCommand(suggestion))
                    .append(SEPARATOR, Component.text(language.text(descriptionKey), GRAY)));
        }

        void field(String labelKey, String value) {
            lines.add(Component.text(language.text(labelKey), GRAY).append(SEPARATOR, Component.text(value, GOLD)));
        }

        void detail(String key, Object... values) {
            lines.add(format(language, key, tone(key), values));
        }

        Language language() { return language; }

        void send() { sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines)); }
    }
}
