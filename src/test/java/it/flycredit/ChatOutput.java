package it.flycredit;

import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

final class ChatOutput {
    private ChatOutput() {}
    static String plain(Component message) {
        return PlainTextComponentSerializer.plainText().serialize(message);
    }
    static Component notice(String body) {
        return argThat((Component c) -> c != null && plain(c).equals("FlyCredit » " + body));
    }
    static Component capture(CommandSender sender) {
        var messages = ArgumentCaptor.forClass(Component.class);
        verify(sender, timeout(3000)).sendMessage(messages.capture());
        return messages.getValue();
    }
    static Component part(Component root, String text) {
        return tree(root).filter(c -> c instanceof TextComponent t && text.equals(t.content()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing text: " + text));
    }
    private static Stream<Component> tree(Component root) {
        return Stream.concat(Stream.of(root), root.children().stream().flatMap(ChatOutput::tree));
    }
}
