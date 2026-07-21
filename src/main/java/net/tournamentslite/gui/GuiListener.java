package net.tournamentslite.gui;

import net.tournamentslite.TournamentsLitePlugin;
import net.tournamentslite.tournament.Tournament;
import net.tournamentslite.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Folia note: InventoryClickEvent is always fired on the region thread that
 * owns the clicking player, so everything below (reading/writing the
 * tournament's thread-safe maps, editing the clicked slot, messaging the
 * player) can run directly in this handler with no extra scheduling. We
 * never need RegionScheduler/EntityScheduler here because we are not
 * reaching into another entity/location - only the event's own player and
 * their own open inventory.
 */
public class GuiListener implements Listener {

    private final TournamentsLitePlugin plugin;

    public GuiListener(TournamentsLitePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof TournamentGuiHolder)) return;

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory().getHolder() != holder) return;

        int slot = event.getSlot();
        Tournament tournament = findBySlot(slot);
        if (tournament == null) return;

        MessageUtil msg = plugin.getMessages();

        if (!player.hasPermission("tournamentslite.use")) {
            player.sendMessage(msg.get("actions.no-permission-signup", null));
            return;
        }

        if (plugin.getTournamentManager().isBanned(player.getUniqueId())) {
            player.sendMessage(msg.get("actions.banned", null));
            return;
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("display_name", tournament.getDisplayName());

        // Any click toggles: not signed up -> sign up, already signed up -> leave.
        if (tournament.isSignedUp(player.getUniqueId())) {
            tournament.leave(player.getUniqueId());
            player.sendMessage(msg.get("actions.left", placeholders));
        } else {
            tournament.signUp(player.getUniqueId());
            player.sendMessage(msg.get("actions.signed-up", placeholders));
        }

        // refresh just the clicked slot in this player's own open inventory
        event.getClickedInventory().setItem(slot, plugin.getGui().buildItem(tournament, player));
    }

    private Tournament findBySlot(int slot) {
        for (Tournament t : plugin.getTournamentManager().getAll().values()) {
            if (t.getSlot() == slot) return t;
        }
        return null;
    }
}
