package it.flycredit;

import it.flycredit.integration.ClaimAccess;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.events.CommentEvent;

/** Updates stock comments and installs newly introduced settings in existing configurations. */
final class ConfigComments {
    private ConfigComments() {}

    private static final String OWNER_RULE =
            "Claim owners can fly. Set flight.allowed-trust-types to allow guests with personal claim trust.";
    private static final String PERSONAL_RULE = "Public, group, or clan trust alone does not grant flight.";
    private static final String OPERATOR_RULE = "Operators and administrators must meet the same claim requirements.";
    private static final String SUBCLAIM_RULE =
            "Subclaims with parent inheritance enabled also accept the configured personal trust types from their parents.";

    // Only the exact stock comments from the previous release have known fingerprints.
    private static final Map<String, String> ENGLISH_REPLACEMENTS = Map.ofEntries(
            Map.entry("5c4c76e5b36dd3f719e190fc8505ce8241e929f83b5d182c64f8565e55509f7d",
                    "List the LuckPerms groups on this server that may receive flight credit."),
            Map.entry("6bbf2302701555cc69943c8bb2afe3a2d37c45dde69cef324dee7356e86d7f1f",
                    "An empty list disables transfers. Players can still use their own flight credit."),
            Map.entry("5f58b1a19c3635a90b9b80f5f9f9f59763ab2d89028257cb2a8cc219744355b3",
                    "Restart the server after changing this list."),
            Map.entry("35f0c004a4ec70a3832634e8606f092fbbcce0755991b9a16da0f8000f730898",
                    "Giving credit requires flycredit.pay, which all players have by default."),
            Map.entry("6b9671eb80cd4fbbc2dd852810c14c7b642fe5c0983e4bc3c67a00796291bf24",
                    "DIRECT requires a group assigned directly to the recipient."),
            Map.entry("4d2bb868db02cb67caf89b29c84b43c96c148596c6b7f23b9dd590dce4984d1b",
                    "INHERITED also accepts groups inherited through LuckPerms."),
            Map.entry("6fabb27a0d2a9711975df77f27c86d9da6d392a2c6b7c5bca31a50aaf3075e25",
                    "Protection covers one landing. The first landing also ends Slow Falling."),
            Map.entry("ce290adb05edeac1742721f43ac5321414e906e8124dd1d8d8a0c7c162f65424",
                    "After a logout or restart, only the time left in the protection window resumes."),
            Map.entry("750295baf4163260f10a71b1a5d6751ffa3a23e4d98a36144fb15c33bb5e16c6",
                    "Flight usage is saved asynchronously. Transfers and reservations use transactions."),
            Map.entry("92afd6518723b31eaca0f8b634490040eda0f91a314c853a102091f8c7f25ad3",
                    OWNER_RULE),
            Map.entry("c80454cafae0f6584bea945853ad89c21277ddf00d0036281685ae5ff97ebae0",
                    SUBCLAIM_RULE),
            Map.entry("a7ba6a5435e22d942ad1f85ad27005c6e06af6c02cee7c1d1592e171893c5852",
                    PERSONAL_RULE),
            Map.entry("d91995f7f7a15fd2aca993e3d31a562f74ba0f4d429d1314f11b0a472437fb7d",
                    OPERATOR_RULE),
            Map.entry("83df87444ffe68d2e371ee078691605542440431616da21013f4a728440c4408",
                    "FlyCredit 1.0.0 — Paper / Purpur 26.3, Java 25"),
            Map.entry("e1520ae963c5fb5feda412286c8df20bb30bdfb628cb6e8c2600156fcba1c611", OWNER_RULE),
            Map.entry("47681ce76f26341381e76947b6d20d6271efa62e06754b25bf49476267a3c58e", OWNER_RULE),
            Map.entry("3e58c32b52c5ac7c581a0658f9172c1b0fa6c03455fcc983063764581371cb92", SUBCLAIM_RULE),
            Map.entry("3073a35c0671047ca653ec7d551f78e522ae79ea9f78ad2d430c38bbab1ee4ee", PERSONAL_RULE),
            Map.entry("c716ed345e4c93ba285572d81d7f139ca5b030e2a0866ee6e9a9c2ea0c660bab", OPERATOR_RULE),
            Map.entry("3947d92795aaf5e48a96b17d1fdcc9fcb2b121793c60c18d4c9768e29f1a82ba",
                    "Players spend no credit on the ground, outside eligible claims, or offline."),
            Map.entry("950a5c71db943e8df5e63d810fd43553da9025d0f2d26bc820548eea8cb40afa",
                    "PlayTimeManager tracks playtime and manages rank promotions."),
            Map.entry("9d45b23b8abff116235e969716ebfb6a486c0fb20a3b34a8bed8d402ed2b0809",
                    "LuckPerms groups that may receive credit through /flycredit give.")
    );

    static void update(Path config) throws IOException, InvalidConfigurationException {
        if (!Files.isRegularFile(config)) return;
        Path target = config.toRealPath();
        String original = Files.readString(target);
        String updated = addMissingTrustSetting(translateStockComments(original));
        if (original.equals(updated)) return;
        Path staged = Files.createTempFile(target.getParent(), ".flycredit-config-", ".tmp");
        try {
            Files.copy(target, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.writeString(staged, updated);
            if (Files.readString(target).equals(original)) {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static String addMissingTrustSetting(String source) throws InvalidConfigurationException {
        // Load the file itself: the plugin's bundled defaults must not mask a missing on-disk setting.
        var config = new YamlConfiguration();
        config.loadFromString(source);
        if (config.contains(ClaimAccess.TRUST_SETTING)) return source;
        if (config.contains("flight") && !config.isConfigurationSection("flight")) {
            throw new InvalidConfigurationException("The flight setting must be a YAML section.");
        }
        config.set(ClaimAccess.TRUST_SETTING, List.of());
        config.setComments(ClaimAccess.TRUST_SETTING, List.of(
                "Personal GriefDefender trust types that allow guests to fly in someone else's claim.",
                "Leave empty for owners only. Use type IDs from GriefDefender and restart after editing."));
        return config.saveToString();
    }

    static String translateStockComments(String source) {
        String[] lines = source.split("(?<=\\n)|(?<=\\r)(?!\\n)", -1);
        var parser = new Yaml(new LoaderOptions().setProcessComments(true));
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        for (var event : parser.parse(new StringReader(source))) {
            if (!(event instanceof CommentEvent comment) || comment.getCommentType() != CommentType.BLOCK) continue;
            String value = comment.getValue().strip();
            String hash = HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            String english = ENGLISH_REPLACEMENTS.get(hash);
            if (english == null) continue;
            int row = comment.getStartMark().getLine();
            if (row >= lines.length) continue;
            String line = lines[row];
            int start = line.indexOf('#');
            if (start < 0 || !line.substring(start + 1).strip().equals(value)) continue;
            lines[row] = line.substring(0, start) + "# " + english + line.substring(line.stripTrailing().length());
        }
        return String.join("", lines);
    }
}
