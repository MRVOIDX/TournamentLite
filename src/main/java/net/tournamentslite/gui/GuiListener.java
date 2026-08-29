package net.tournamentslite.gui;

import io.papermc.paper.dialog.Dialog;
import net.tournamentslite.TournamentsLitePlugin;
import net.tournamentslite.tournament.Tournament;
import net.tournamentslite.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Folia note: InventoryClickEvent is always fired on the region thread that
 * owns the clicking player, so everything below (reading/writing the
 * tournament's thread-safe maps, editing the clicked slot, messaging the
 * player, showing them a Dialog) can run directly in this handler with no
 * extra scheduling. We never need RegionScheduler/EntityScheduler here
 * because we are not reaching into another entity/location - only the
 * event's own player and their own open inventory/dialog.
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

        // Any click toggles: not signed up -> sign up, already signed up -> leave.
        boolean signingUp = !tournament.isSignedUp(player.getUniqueId());

        // "gui.confirmation-dialog.enabled" in config.yml - when off, this
        // reproduces the plugin's original behavior (instant toggle, no
        // popup). When on, a native Dialog popup (see
        // ConfirmationDialogFactory) asks the player to confirm first, and
        // only actually toggles their sign-up if they press the confirm
        // button.
        boolean dialogsEnabled = plugin.getConfig().getBoolean("gui.confirmation-dialog.enabled", true);

        if (!dialogsEnabled) {
            applyToggle(player, tournament, signingUp);
            event.getClickedInventory().setItem(slot, plugin.getGui().buildItem(tournament, player));
            return;
        }

        Dialog dialog = ConfirmationDialogFactory.build(plugin, player, tournament, signingUp,
                () -> {
                    applyToggle(player, tournament, signingUp);
                    refreshSlot(player, tournament);
                });
        player.showDialog(dialog);
    }

    /**
     * Actually flips the sign-up state and sends the player their feedback
     * message. Shared by both paths above (instant-toggle when dialogs are
     * disabled, and the dialog's confirm-button callback when they're on)
     * so the two behave identically other than the extra confirmation step.
     */
    private void applyToggle(Player player, Tournament tournament, boolean signingUp) {
        MessageUtil msg = plugin.getMessages();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("display_name", tournament.getDisplayName());

        if (signingUp) {
            tournament.signUp(player.getUniqueId());
            player.sendMessage(msg.get("actions.signed-up", placeholders));
        } else {
            tournament.leave(player.getUniqueId());
            player.sendMessage(msg.get("actions.left", placeholders));
        }
    }

    /**
     * Re-renders just this tournament's slot in whatever tournament GUI the
     * player currently has open. Needed for the dialog path since, unlike
     * the instant-toggle path, we don't have the original InventoryClickEvent's
     * clicked inventory handy inside the confirm callback - the dialog sits
     * on top of that same inventory the whole time (per Paper's Dialog API,
     * closing a dialog returns to whatever screen was open underneath it),
     * so it's still reachable through the player's currently open inventory.
     */
    private void refreshSlot(Player player, Tournament tournament) {
        if (!player.isOnline()) return;
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof TournamentGuiHolder
                && tournament.getSlot() >= 0 && tournament.getSlot() < top.getSize()) {
            top.setItem(tournament.getSlot(), plugin.getGui().buildItem(tournament, player));
        }
    }

    private Tournament findBySlot(int slot) {
        for (Tournament t : plugin.getTournamentManager().getAll().values()) {
            if (t.getSlot() == slot) return t;
        }
        return null;
    }
}
