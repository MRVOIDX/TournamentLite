package net.tournamentslite;

import net.tournamentslite.command.TournamentCommand;
import net.tournamentslite.gui.GuiListener;
import net.tournamentslite.gui.TournamentGui;
import net.tournamentslite.tournament.TournamentManager;
import net.tournamentslite.util.MessageUtil;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Folia-native tournament sign-up plugin.
 *
 * Threading model summary (see individual classes for details):
 *  - TournamentManager: pure in-memory + file I/O, no world/entity calls.
 *      Picking up manually-edited leaderboard files, and autosave, both run
 *      on the AsyncScheduler (never block a region thread on disk I/O).
 *  - TournamentGui / GuiListener: only ever act on the player that owns the
 *      event/command already calling them, so no extra scheduling needed.
 *  - TournamentCommand#tpsigned: uses Entity#teleportAsync, the Paper/Folia
 *      safe cross-region teleport, instead of the legacy synchronous
 *      teleport() which is unsafe/blocked on Folia.
 *
 * We deliberately never touch org.bukkit.scheduler.BukkitScheduler
 * (Bukkit.getScheduler()) anywhere in this plugin, since it is deprecated
 * and unsafe under Folia's regionised threading.
 */
public final class TournamentsLitePlugin extends JavaPlugin {

    private TournamentManager tournamentManager;
    private MessageUtil messages;
    private TournamentGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.messages = new MessageUtil(this);
        this.messages.load();

        this.tournamentManager = new TournamentManager(this);
        this.tournamentManager.load();

        this.gui = new TournamentGui(this);

        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        TournamentCommand commandHandler = new TournamentCommand(this);
        var command = getCommand("tl");
        if (command != null) {
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);
        }

        tournamentManager.startLeaderboardRefreshTask();
        tournamentManager.startAutosaveTask();

        getLogger().info("TournamentsLite enabled (Folia-native scheduling active).");
    }

    @Override
    public void onDisable() {
        if (tournamentManager != null) {
            tournamentManager.saveData();
        }
    }

    public void reloadAll() {
        messages.load();
        tournamentManager.load();
    }

    public TournamentManager getTournamentManager() {
        return tournamentManager;
    }

    public MessageUtil getMessages() {
        return messages;
    }

    public TournamentGui getGui() {
        return gui;
    }
}
