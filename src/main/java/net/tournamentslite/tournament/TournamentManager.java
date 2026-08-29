package net.tournamentslite.tournament;

import net.tournamentslite.TournamentsLitePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Owns the tournament map and all disk I/O.
 *
 * Each tournament lives in its own file under plugins/TournamentsLite/tournaments/
 * (e.g. uhc.yml, cartpvp.yml, clanwars.yml) so you can open just one file
 * and type in that week's winners by hand. Sign-ups and the ban list are
 * shared plumbing data, not something you'd hand-edit, so those still live
 * together in data.yml.
 *
 * IMPORTANT (Folia threading): this class never schedules world/entity work.
 * Loading/saving YAML is plain file I/O and in-memory map mutation. The
 * periodic "pick up manual leaderboard edits from disk" sweep is real disk
 * I/O, so it is dispatched on the AsyncScheduler - never on a region thread,
 * so a slow disk can never cause a TPS hiccup.
 */
public class TournamentManager {

    private static final String[] DEFAULT_TOURNAMENT_IDS = {"uhc", "cartpvp", "clanwars"};

    private final TournamentsLitePlugin plugin;
    private final Map<String, Tournament> tournaments = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> bannedPlayers = ConcurrentHashMap.newKeySet();

    private File tournamentsFolder;
    private File dataFile;
    private FileConfiguration dataConfig;

    public TournamentManager(TournamentsLitePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();

        tournamentsFolder = new File(plugin.getDataFolder(), "tournaments");
        if (!tournamentsFolder.exists()) {
            tournamentsFolder.mkdirs();
        }

        // First run: seed the folder with the 3 default tournament files
        // bundled in the jar, so there's always something to edit.
        if (isEmpty(tournamentsFolder)) {
            for (String id : DEFAULT_TOURNAMENT_IDS) {
                plugin.saveResource("tournaments/" + id + ".yml", false);
            }
        }

        tournaments.clear();
        File[] files = tournamentsFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase();
                try {
                    Tournament tournament = parseTournamentFile(id, YamlConfiguration.loadConfiguration(file));
                    tournaments.put(id, tournament);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to load tournaments/" + file.getName() + ", skipping.", ex);
                }
            }
        }

        loadData();
    }

    private boolean isEmpty(File folder) {
        File[] files = folder.listFiles();
        return files == null || files.length == 0;
    }

    private Tournament parseTournamentFile(String id, FileConfiguration f) {
        String displayName = f.getString("display-name", capitalize(id));
        int slot = f.getInt("slot", 0);
        Material material = Material.matchMaterial(f.getString("material", "STONE"));
        if (material == null) material = Material.STONE;

        DayOfWeek weekday = null;
        String weekdayRaw = f.getString("weekday", "");
        if (weekdayRaw != null && !weekdayRaw.isBlank()) {
            try {
                weekday = DayOfWeek.valueOf(weekdayRaw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Tournament '" + id + "': invalid weekday '" + weekdayRaw + "', ignoring.");
            }
        }

        Tournament tournament = new Tournament(id, displayName, slot, material, weekday);

        String animItemRaw = f.getString("animation-item", "");
        if (animItemRaw != null && !animItemRaw.isBlank()) {
            Material animItem = Material.matchMaterial(animItemRaw.trim());
            if (animItem != null) {
                tournament.setAnimationItem(animItem);
            } else {
                plugin.getLogger().warning("Tournament '" + id + "': invalid animation-item '" + animItemRaw + "', ignoring.");
            }
        }

        String dialogIconRaw = f.getString("dialog-icon", "");
        if (dialogIconRaw != null && !dialogIconRaw.isBlank()) {
            Material dialogIcon = Material.matchMaterial(dialogIconRaw.trim());
            if (dialogIcon != null) {
                tournament.setDialogIcon(dialogIcon);
            } else {
                plugin.getLogger().warning("Tournament '" + id + "': invalid dialog-icon '" + dialogIconRaw + "', ignoring.");
            }
        }

        applyLeaderboardSection(tournament, f);
        return tournament;
    }

    private void applyLeaderboardSection(Tournament tournament, FileConfiguration f) {
        ConfigurationSection lb = f.getConfigurationSection("leaderboard");
        Map<Integer, String> names = new HashMap<>();
        if (lb != null) {
            for (String key : lb.getKeys(false)) {
                try {
                    int rank = Integer.parseInt(key.trim());
                    String name = lb.getString(key, "");
                    if (name != null && !name.isBlank()) {
                        names.put(rank, name);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        tournament.replaceLeaderboard(names);
    }

    private String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------- persistence of a single tournament file ----------------

    /**
     * Writes one tournament's full file (metadata + leaderboard) back to
     * disk. Called after admin commands change something, so the file you
     * hand-edit always reflects the current in-memory state too.
     */
    public void saveTournamentFile(Tournament t) {
        File file = new File(tournamentsFolder, t.getId() + ".yml");
        YamlConfiguration f = new YamlConfiguration();
        f.set("display-name", t.getDisplayName());
        f.set("slot", t.getSlot());
        f.set("material", t.getMaterial().name());
        f.set("weekday", t.getWeekday() != null ? t.getWeekday().name() : "");
        f.set("animation-item", t.getAnimationItemRaw() != null ? t.getAnimationItemRaw().name() : "");
        f.set("dialog-icon", t.getDialogIconRaw() != null ? t.getDialogIconRaw().name() : "");
        for (Map.Entry<Integer, String> e : t.getLeaderboardNames().entrySet()) {
            f.set("leaderboard." + e.getKey(), e.getValue());
        }
        // always show all 5 rank keys in the file, even if empty, so it's
        // obvious at a glance where to type each winner in.
        for (int rank = 1; rank <= 5; rank++) {
            if (!t.getLeaderboardNames().containsKey(rank)) {
                f.set("leaderboard." + rank, "");
            }
        }
        try {
            f.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save tournaments/" + file.getName(), e);
        }
    }

    private void deleteTournamentFile(String id) {
        File file = new File(tournamentsFolder, id + ".yml");
        if (file.exists()) file.delete();
    }

    /**
     * Re-reads just the leaderboard section of every tournament file from
     * disk and applies it in memory. This is what makes hand-editing a
     * tournament's file "just work" without needing /tl reload - real disk
     * I/O, so it must never run on a region thread (see startLeaderboardRefreshTask).
     */
    public void refreshLeaderboardsFromDisk() {
        for (Tournament t : tournaments.values()) {
            File file = new File(tournamentsFolder, t.getId() + ".yml");
            if (!file.exists()) continue;
            try {
                FileConfiguration f = YamlConfiguration.loadConfiguration(file);
                applyLeaderboardSection(t, f);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Could not refresh tournaments/" + file.getName(), ex);
            }
        }
    }

    // ---------------- persistence of sign-ups/bans ----------------

    private void loadData() {
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create data.yml", e);
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        bannedPlayers.clear();
        for (String uuidStr : dataConfig.getStringList("banned")) {
            try {
                bannedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {
            }
        }

        for (Tournament tournament : tournaments.values()) {
            ConfigurationSection signups = dataConfig.getConfigurationSection(tournament.getId() + ".signups");
            if (signups == null) continue;
            for (String uuidStr : signups.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    tournament.getSignups().put(uuid, signups.getLong(uuidStr));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    /**
     * Saves sign-ups + the ban list to data.yml. Safe to call from an async
     * task since it only touches the plain YamlConfiguration object + disk.
     */
    public synchronized void saveData() {
        if (dataConfig == null) return;
        dataConfig.set("banned", bannedPlayers.stream().map(UUID::toString).toList());
        for (Tournament tournament : tournaments.values()) {
            String base = tournament.getId();
            dataConfig.set(base, null);
            for (Map.Entry<UUID, Long> e : tournament.getSignups().entrySet()) {
                dataConfig.set(base + ".signups." + e.getKey(), e.getValue());
            }
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save data.yml", e);
        }
    }

    // ---------------- scheduled tasks ----------------

    /**
     * Starts the repeating disk-refresh task on the AsyncScheduler (real
     * file I/O - never on a region thread) so manually-edited leaderboard
     * names show up in the GUI without an explicit /tl reload.
     */
    public void startLeaderboardRefreshTask() {
        long seconds = Math.max(5L, plugin.getConfig().getLong("leaderboard-refresh-interval-seconds", 30));
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                task -> refreshLeaderboardsFromDisk(),
                seconds, seconds, TimeUnit.SECONDS);
    }

    public void startAutosaveTask() {
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                task -> saveData(),
                5, 5, TimeUnit.MINUTES);
    }

    // ---------------- accessors ----------------

    public Tournament get(String id) {
        if (id == null) return null;
        return tournaments.get(id.toLowerCase());
    }

    public Map<String, Tournament> getAll() {
        return tournaments;
    }

    public boolean isSlotTaken(int slot, String excludingId) {
        for (Tournament t : tournaments.values()) {
            if (t.getSlot() == slot && !t.getId().equalsIgnoreCase(excludingId)) return true;
        }
        return false;
    }

    public void add(Tournament tournament) {
        tournaments.put(tournament.getId().toLowerCase(), tournament);
        saveTournamentFile(tournament);
    }

    public boolean remove(String id) {
        Tournament removed = tournaments.remove(id.toLowerCase());
        if (removed != null) {
            deleteTournamentFile(removed.getId());
            saveData();
            return true;
        }
        return false;
    }

    // ---------------- global tournament ban list ----------------

    public boolean isBanned(UUID uuid) {
        return bannedPlayers.contains(uuid);
    }

    public boolean ban(UUID uuid) {
        boolean added = bannedPlayers.add(uuid);
        if (added) {
            for (Tournament t : tournaments.values()) {
                t.leave(uuid);
            }
            saveData();
        }
        return added;
    }

    public boolean unban(UUID uuid) {
        boolean removed = bannedPlayers.remove(uuid);
        if (removed) saveData();
        return removed;
    }
}
