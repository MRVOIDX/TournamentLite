package net.tournamentslite.command;

import net.tournamentslite.TournamentsLitePlugin;
import net.tournamentslite.tournament.Tournament;
import net.tournamentslite.tournament.TournamentManager;
import net.tournamentslite.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Folia note on /tl tpsigned: teleporting is done with Entity#teleportAsync,
 * which is the Paper/Folia-safe cross-region teleport. It internally
 * schedules the actual move onto the region that owns the target location
 * (and, on Folia, the region that owns the entity), so we never need to
 * manually reach for a RegionScheduler/EntityScheduler here - calling
 * teleportAsync from this command (already running on the sender's own
 * region thread) is sufficient and won't block or desync anything.
 */
public class TournamentCommand implements CommandExecutor, TabCompleter {

    private final TournamentsLitePlugin plugin;

    public TournamentCommand(TournamentsLitePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageUtil msg = plugin.getMessages();
        TournamentManager manager = plugin.getTournamentManager();

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Console cannot open the GUI. Use subcommands instead.");
                return true;
            }
            if (!player.hasPermission("tournamentslite.use")) {
                sender.sendMessage(msg.get("gui.no-permission", null));
                return true;
            }
            player.openInventory(plugin.getGui().build(player));
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "help" -> sendHelp(sender, msg);
            case "list" -> handleList(sender, manager, msg);
            case "reload" -> handleReload(sender, msg);
            case "create" -> handleCreate(sender, args, manager, msg);
            case "remove" -> handleRemove(sender, args, manager, msg);
            case "tpsigned" -> handleTpSigned(sender, args, manager, msg);
            case "signup" -> handleSignup(sender, args, manager, msg);
            case "kick" -> handleKick(sender, args, manager, msg);
            case "ban" -> handleBan(sender, args, manager, msg);
            case "unban" -> handleUnban(sender, args, manager, msg);
            case "setwinner" -> handleSetWinner(sender, args, manager, msg);
            case "clearwinners" -> handleClearWinners(sender, args, manager, msg);
            default -> sendHelp(sender, msg);
        }
        return true;
    }

    private void sendHelp(CommandSender sender, MessageUtil msg) {
        for (String line : msg.getList("commands.help", null)) {
            sender.sendMessage(line);
        }
    }

    private boolean requireAdmin(CommandSender sender, MessageUtil msg) {
        if (!sender.hasPermission("tournamentslite.admin")) {
            sender.sendMessage(msg.get("gui.no-permission", null));
            return false;
        }
        return true;
    }

    private void handleList(CommandSender sender, TournamentManager manager, MessageUtil msg) {
        sender.sendMessage(msg.get("commands.list-header", null));
        for (Tournament t : manager.getAll().values()) {
            Map<String, String> ph = new HashMap<>();
            ph.put("id", t.getId());
            ph.put("slot", String.valueOf(t.getSlot()));
            ph.put("signed_count", String.valueOf(t.getSignupCount()));
            ph.put("weekday", t.getWeekday() != null ? t.getWeekday().name() : "none");
            sender.sendMessage(msg.get("commands.list-entry", ph));
        }
    }

    private void handleReload(CommandSender sender, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        plugin.getTournamentManager().saveData();
        plugin.reloadAll();
        sender.sendMessage(msg.get("commands.reloaded", null));
    }

    private void handleCreate(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (args.length < 5) {
            sender.sendMessage(msg.get("commands.create-usage", null));
            return;
        }
        String id = args[1].toLowerCase();
        int slot;
        try {
            slot = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg.get("commands.create-usage", null));
            return;
        }
        Material material = Material.matchMaterial(args[3].toUpperCase());
        if (material == null) {
            sender.sendMessage(msg.get("commands.create-usage", null));
            return;
        }
        DayOfWeek weekday = null;
        if (!args[4].equalsIgnoreCase("none")) {
            try {
                weekday = DayOfWeek.valueOf(args[4].toUpperCase());
            } catch (IllegalArgumentException e) {
                sender.sendMessage(msg.get("commands.create-usage", null));
                return;
            }
        }
        if (manager.isSlotTaken(slot, id)) {
            Tournament existing = manager.getAll().values().stream()
                    .filter(t -> t.getSlot() == slot).findFirst().orElse(null);
            Map<String, String> ph = new HashMap<>();
            ph.put("slot", String.valueOf(slot));
            ph.put("other", existing != null ? existing.getId() : "?");
            sender.sendMessage(msg.get("commands.create-slot-taken", ph));
            return;
        }

        Tournament tournament = new Tournament(id, capitalize(id), slot, material, weekday);
        manager.add(tournament);

        Map<String, String> ph = new HashMap<>();
        ph.put("id", id);
        ph.put("slot", String.valueOf(slot));
        sender.sendMessage(msg.get("commands.created", ph));
    }

    private void handleRemove(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (args.length < 2) {
            sender.sendMessage(msg.get("commands.remove-usage", null));
            return;
        }
        String id = args[1];
        if (manager.remove(id)) {
            sender.sendMessage(msg.get("commands.removed", Map.of("id", id)));
        } else {
            sender.sendMessage(msg.get("gui.unknown-tournament", Map.of("input", id)));
        }
    }

    private void handleTpSigned(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (!(sender instanceof Player admin)) {
            sender.sendMessage("Only a player can run tpsigned (teleports to their own location).");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(msg.get("commands.tpsigned-usage", null));
            return;
        }
        Tournament t = manager.get(args[1]);
        if (t == null) {
            sender.sendMessage(msg.get("gui.unknown-tournament", Map.of("input", args[1])));
            return;
        }
        if (t.getSignupCount() == 0) {
            sender.sendMessage(msg.get("commands.tpsigned-none-signed", Map.of("display_name", t.getDisplayName())));
            return;
        }

        org.bukkit.Location destination = admin.getLocation().clone();
        long delayTicks = plugin.getConfig().getLong("teleport-animation-delay-ticks", 30);
        int teleported = 0;
        for (UUID uuid : t.getSignups().keySet()) {
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) continue;
            // Private, per-player pre-teleport animation (totem-style flash +
            // themed particle + sound, visible only to this player), then a
            // Folia-safe delayed teleport - see TeleportAnimator for details.
            net.tournamentslite.teleport.TeleportAnimator.animateThenTeleport(
                    plugin, target, destination, t.getAnimationItem(), delayTicks);
            teleported++;
        }

        sender.sendMessage(msg.get("commands.tpsigned-done", Map.of("count", String.valueOf(teleported))));
    }

    private void handleSignup(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (args.length < 3) {
            sender.sendMessage(msg.get("commands.signup-usage", null));
            return;
        }
        Tournament t = manager.get(args[2]);
        if (t == null) {
            sender.sendMessage(msg.get("gui.unknown-tournament", Map.of("input", args[2])));
            return;
        }
        // Bukkit.getOfflinePlayer(String) can do a blocking UUID lookup for
        // a name the server has never seen before - acceptable here since
        // this is a rarely-run admin command, not something in a hot path.
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);

        if (manager.isBanned(target.getUniqueId())) {
            sender.sendMessage(msg.get("commands.signup-banned", Map.of("player", args[1])));
            return;
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("player", args[1]);
        ph.put("id", t.getId());

        if (!t.signUp(target.getUniqueId())) {
            sender.sendMessage(msg.get("commands.signup-already", ph));
            return;
        }
        manager.saveData();
        sender.sendMessage(msg.get("commands.signup-done", ph));
    }

    private void handleKick(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (args.length < 3) {
            sender.sendMessage(msg.get("commands.kick-usage", null));
            return;
        }
        Tournament t = manager.get(args[2]);
        if (t == null) {
            sender.sendMessage(msg.get("gui.unknown-tournament", Map.of("input", args[2])));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);

        Map<String, String> ph = new HashMap<>();
        ph.put("player", args[1]);
        ph.put("id", t.getId());

        if (!t.leave(target.getUniqueId())) {
            sender.sendMessage(msg.get("commands.kick-not-signed", ph));
            return;
        }
        manager.saveData();
        sender.sendMessage(msg.get("commands.kick-done", ph));
    }

    private void handleBan(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (args.length < 2) {
            sender.sendMessage(msg.get("commands.ban-usage", null));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        Map<String, String> ph = Map.of("player", args[1]);

        if (!manager.ban(target.getUniqueId())) {
            sender.sendMessage(msg.get("commands.ban-already", ph));
            return;
        }
        sender.sendMessage(msg.get("commands.ban-done", ph));
    }

    private void handleUnban(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (args.length < 2) {
            sender.sendMessage(msg.get("commands.unban-usage", null));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        Map<String, String> ph = Map.of("player", args[1]);

        if (!manager.unban(target.getUniqueId())) {
            sender.sendMessage(msg.get("commands.unban-not-banned", ph));
            return;
        }
        sender.sendMessage(msg.get("commands.unban-done", ph));
    }

    private void handleSetWinner(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (args.length < 4) {
            sender.sendMessage(msg.get("commands.setwinner-usage", null));
            return;
        }
        Tournament t = manager.get(args[1]);
        if (t == null) {
            sender.sendMessage(msg.get("gui.unknown-tournament", Map.of("input", args[1])));
            return;
        }
        int rank;
        try {
            rank = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg.get("commands.setwinner-usage", null));
            return;
        }
        if (rank < 1 || rank > 5) {
            sender.sendMessage(msg.get("commands.setwinner-bad-rank", null));
            return;
        }
        // support names with spaces: "/tl setwinner clanwars 1 Team Rocket"
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));

        t.setLeaderboardName(rank, name);
        manager.saveTournamentFile(t);

        Map<String, String> ph = new HashMap<>();
        ph.put("id", t.getId());
        ph.put("rank", String.valueOf(rank));
        ph.put("name", name);
        sender.sendMessage(msg.get("commands.setwinner-done", ph));
    }

    private void handleClearWinners(CommandSender sender, String[] args, TournamentManager manager, MessageUtil msg) {
        if (!requireAdmin(sender, msg)) return;
        if (args.length < 2) {
            sender.sendMessage(msg.get("commands.clearwinners-usage", null));
            return;
        }
        Tournament t = manager.get(args[1]);
        if (t == null) {
            sender.sendMessage(msg.get("gui.unknown-tournament", Map.of("input", args[1])));
            return;
        }
        t.clearLeaderboard();
        manager.saveTournamentFile(t);
        sender.sendMessage(msg.get("commands.clearwinners-done", Map.of("id", t.getId())));
    }

    private String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> subs = List.of("list", "reload", "create", "remove", "tpsigned", "signup", "kick",
                "ban", "unban", "setwinner", "clearwinners", "help");
        if (args.length == 1) {
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && List.of("remove", "tpsigned", "setwinner", "clearwinners").contains(args[0].toLowerCase())) {
            return plugin.getTournamentManager().getAll().keySet().stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && List.of("signup", "kick", "ban", "unban").contains(args[0].toLowerCase())) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 3 && List.of("signup", "kick").contains(args[0].toLowerCase())) {
            return plugin.getTournamentManager().getAll().keySet().stream()
                    .filter(s -> s.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setwinner")) {
            return List.of("1", "2", "3", "4", "5").stream()
                    .filter(s -> s.startsWith(args[2])).collect(Collectors.toList());
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("create")) {
            List<String> days = new ArrayList<>();
            for (DayOfWeek d : DayOfWeek.values()) days.add(d.name());
            days.add("none");
            return days.stream().filter(s -> s.toLowerCase().startsWith(args[4].toLowerCase())).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
