package ai.resourcepack.addons.skateboards;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the skateboard says, and the prefix it says it behind, out of
 * {@code config.yml}.
 *
 * <p><strong>The defaults live here rather than only in the shipped
 * config.yml</strong>, and a missing key falls back to one. A server owner
 * who deletes a line, or who has last version's file on disk when this one
 * adds a message, gets the English sentence rather than {@code null} — which
 * is what a plugin that reads its strings straight out of a
 * {@link FileConfiguration} does, and it reads as the plugin being broken.
 *
 * <p>Placeholders are {@code {name}} and are replaced by whatever the caller
 * passes; an unknown one is left alone rather than blanked, so a typo in a
 * config is visible in chat instead of silent.
 */
final class Messages {

    /** Every message, by its config key, with the sentence to use when the config has none. */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("players-only", "Only a player can hold a skateboard.");
        DEFAULTS.put("given", "Here's a board. Right-click the ground to set it down, W to push, "
                + "S to push it backwards, hold sprint to tuck, hold shift to lie back, space to "
                + "ollie, A/D in the air to spin it, and jump at a wall to ride it. "
                + "Tap shift to step off. "
                + "/skateboard stance right if you ride right foot forward.");
        DEFAULTS.put("no-item", "The engine has no skateboard item yet - has it finished loading? Try /rp reload.");
        DEFAULTS.put("placed", "Board's down. Right-click it to step on.");
        DEFAULTS.put("place-failed", "Couldn't set a board down there.");
        DEFAULTS.put("stance-current", "You ride &f{stance} foot forward&7. /skateboard stance left|right to change.");
        DEFAULTS.put("stance-set", "You ride &f{stance} foot forward&7 now.");
        DEFAULTS.put("stance-set-riding", "You ride &f{stance} foot forward&7 now - turning you round.");
        DEFAULTS.put("landed", "Landed it.");
        DEFAULTS.put("fakie", "Fakie!");
        DEFAULTS.put("bailed", "&cBailed! &7Land it straight (or straight backwards) next time.");
        DEFAULTS.put("reloaded", "Config reloaded.");
        DEFAULTS.put("park-list", "&f{count}&7 skatepark pieces: &f{pieces}&7. "
                + "/skatepark <piece> for one, /skatepark all for the lot.");
        DEFAULTS.put("park-given", "Here's a &f{piece}&7. Right-click the ground to place it, punch it to take it back.");
        DEFAULTS.put("park-all", "There you go - all &f{count}&7 pieces. Build something.");
        DEFAULTS.put("park-unknown", "There's no park piece called &f{piece}&7. /skatepark to see them all.");
        DEFAULTS.put("park-none", "The engine has no park pieces yet - has it finished loading? Try /rp reload.");
    }

    /** The prefix every message is sent behind. Empty is allowed, and means none. */
    private static final String DEFAULT_PREFIX = "&b[Skateboards] &7";

    private final String prefix;
    private final Map<String, String> messages = new LinkedHashMap<>();

    Messages(FileConfiguration config) {
        this.prefix = colour(config.getString("prefix", DEFAULT_PREFIX));
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            messages.put(entry.getKey(),
                    colour(config.getString("messages." + entry.getKey(), entry.getValue())));
        }
    }

    /** The message for {@code key}, prefixed, with {@code {placeholder} value} pairs filled in. */
    String get(String key, String... placeholders) {
        String text = messages.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        // The prefix's own colour, re-stated after any &f or &c inside the
        // message has had its say, is not something to be clever about: the
        // message says what colour it wants and the prefix says what it wants.
        return prefix + text;
    }

    /** The message with no prefix — for anything that is not chat. */
    String plain(String key, String... placeholders) {
        String full = get(key, placeholders);
        return prefix.isEmpty() ? full : full.substring(prefix.length());
    }

    String prefix() {
        return prefix;
    }

    private static String colour(String raw) {
        return raw == null ? "" : ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** The keys and sentences the shipped config.yml is generated from. */
    static Map<String, String> defaults() {
        return DEFAULTS;
    }

    static String defaultPrefix() {
        return DEFAULT_PREFIX;
    }
}
