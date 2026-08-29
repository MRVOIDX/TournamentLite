package net.tournamentslite.gui;

import net.tournamentslite.TournamentsLitePlugin;
import net.tournamentslite.tournament.Tournament;
import net.tournamentslite.tournament.TournamentManager;
import net.tournamentslite.util.ItemBuilder;
import net.tournamentslite.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure UI-building code: reads config/messages + already-loaded, in-memory
 * tournament data and produces ItemStacks/Inventories. It never touches
 * chunks or other entities, so building/opening a menu for a player is safe
 * to do directly inside whatever thread already owns that player (i.e. a
 * command execution or an existing event for that player) - no extra
 * scheduling needed here on Folia.
 */
public class TournamentGui {

    private final TournamentsLitePlugin plugin;

    public TournamentGui(TournamentsLitePlugin plugin) {
        this.plugin = plugin;
    }

    public Inventory build(Player viewer) {
        TournamentManager manager = plugin.getTournamentManager();
        MessageUtil msg = plugin.getMessages();

        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("gui.rows", 3)));
        int size = rows * 9;
        String title = MessageUtil.color(msg.raw("gui.title"));

        TournamentGuiHolder holder = new TournamentGuiHolder();
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        boolean fillEmpty = plugin.getConfig().getBoolean("gui.fill-empty", true);
        if (fillEmpty) {
            Material filler = Material.matchMaterial(plugin.getConfig().getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE"));
            if (filler == null) filler = Material.GRAY_STAINED_GLASS_PANE;
            ItemStack fillerItem = new ItemBuilder(filler).name(" ").build();
            for (int i = 0; i < size; i++) {
                inv.setItem(i, fillerItem);
            }
        }

        for (Tournament tournament : manager.getAll().values()) {
            if (tournament.getSlot() < 0 || tournament.getSlot() >= size) continue;
            inv.setItem(tournament.getSlot(), buildItem(tournament, viewer));
        }

        return inv;
    }

    public ItemStack buildItem(Tournament tournament, Player viewer) {
        MessageUtil msg = plugin.getMessages();

        boolean signed = tournament.isSignedUp(viewer.getUniqueId());

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("display_name", tournament.getDisplayName());
        placeholders.put("schedule", formatSchedule(tournament, msg));
        placeholders.put("status", signed ? msg.raw("item.status-signed") : msg.raw("item.status-not-signed"));
        placeholders.put("time_remaining", formatTimeRemaining(tournament, msg));

        for (int i = 1; i <= 5; i++) {
            placeholders.put("top_" + i, buildLeaderboardLine(tournament, i, msg));
        }

        List<String> lore = msg.getList("item.lore", placeholders);
        String displayName = MessageUtil.color(msg.get("item.display-name", placeholders));

        return new ItemBuilder(tournament.getMaterial())
                .name(displayName)
                .lore(lore)
                .build();
    }

    private String buildLeaderboardLine(Tournament tournament, int rank, MessageUtil msg) {
        String name = tournament.getLeaderboardName(rank);

        String color = switch (rank) {
            case 1, 2 -> "&c";
            case 3 -> "&6";
            case 4 -> "&e";
            default -> "&7";
        };

        String playerName = name.isBlank() ? msg.raw("item.leaderboard-empty-name") : name;

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("rank", String.valueOf(rank));
        placeholders.put("color", color);
        placeholders.put("player", playerName);
        return msg.get("item.leaderboard-line", placeholders);
    }

    private String formatSchedule(Tournament tournament, MessageUtil msg) {
        DayOfWeek weekday = tournament.getWeekday();
        if (weekday == null) {
            return msg.raw("item.no-schedule-text");
        }
        String dayName = weekday.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
        return msg.get("item.schedule-format", Map.of("day", dayName));
    }

    private String formatTimeRemaining(Tournament tournament, MessageUtil msg) {
        DayOfWeek weekday = tournament.getWeekday();
        if (weekday == null) {
            return msg.raw("item.no-schedule-text");
        }

        ZonedDateTime now = ZonedDateTime.now();
        if (now.getDayOfWeek() == weekday) {
            return msg.raw("item.happening-today-text");
        }

        ZonedDateTime next = now.with(TemporalAdjusters.next(weekday))
                .toLocalDate().atStartOfDay(now.getZone());

        long remainingMillis = Duration.between(now, next).toMillis();
        if (remainingMillis <= 0) {
            return msg.raw("item.happening-today-text");
        }

        long seconds = remainingMillis / 1000;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
    }
}
