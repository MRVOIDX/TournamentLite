package net.tournamentslite.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure text/formatting helper - no Bukkit world/entity calls, so it is safe
 * to call from any thread on Folia (main, region, async, whichever).
 */
public class MessageUtil {

    private final Plugin plugin;
    private File messagesFile;
    private FileConfiguration messages;

    public MessageUtil(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);

        // fill in any keys missing from an older user copy using the jar defaults
        try (InputStream defStream = plugin.getResource("messages.yml")) {
            if (defStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                messages.setDefaults(defaults);
                messages.options().copyDefaults(true);
                messages.save(messagesFile);
            }
        } catch (IOException ignored) {
        }
    }

    public String raw(String path) {
        return messages.getString(path, "");
    }

    public List<String> rawList(String path) {
        return messages.getStringList(path);
    }

    public String get(String path, Map<String, String> placeholders) {
        String value = messages.getString(path, "");
        return format(value, placeholders);
    }

    public List<String> getList(String path, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>();
        for (String line : messages.getStringList(path)) {
            result.add(format(line, placeholders));
        }
        return result;
    }

    public String format(String input, Map<String, String> placeholders) {
        return color(substitute(input, placeholders));
    }

    public static String color(String input) {
        if (input == null) return "";
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    /**
     * Same placeholder substitution as {@link #format}, but without the
     * final '&'-to-ChatColor conversion, so the raw '&'-coded string can
     * instead be handed straight to an Adventure legacy deserializer (see
     * {@link #component}). Kept separate so both callers share one
     * placeholder implementation instead of drifting apart over time.
     */
    private String substitute(String input, Map<String, String> placeholders) {
        if (input == null) return "";
        String result = input;
        if (result.contains("{prefix}")) {
            result = result.replace("{prefix}", raw("prefix"));
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                result = result.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return result;
    }

    /**
     * Reads a single string at {@code path}, applies placeholders/prefix,
     * and returns it as an Adventure {@link Component} (still honoring '&'
     * color codes) rather than a legacy ChatColor string. This is what the
     * Dialog API (titles, body lines, button labels/tooltips) needs, since
     * it speaks Components rather than legacy strings.
     */
    public Component component(String path, Map<String, String> placeholders) {
        return toComponent(substitute(messages.getString(path, ""), placeholders));
    }

    /** Same as {@link #component}, but for a string you already pulled out of config (e.g. one line of a list). */
    public Component componentFromRaw(String raw, Map<String, String> placeholders) {
        return toComponent(substitute(raw, placeholders));
    }

    /** Reads a string list at {@code path} and returns each line as a placeholder-applied Component, in order. */
    public List<Component> componentList(String path, Map<String, String> placeholders) {
        List<Component> result = new ArrayList<>();
        for (String line : messages.getStringList(path)) {
            result.add(componentFromRaw(line, placeholders));
        }
        return result;
    }

    private static Component toComponent(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        return LegacyComponentSerializer.legacyAmpersand().deserialize(input);
    }
}
