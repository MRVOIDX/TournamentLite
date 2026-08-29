package net.tournamentslite.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marker holder so GuiListener can reliably tell "this inventory is one of
 * ours" apart from any other open inventory, without relying on title
 * string matching (which breaks once titles become player-specific).
 */
public class TournamentGuiHolder implements InventoryHolder {

    private Inventory inventory;

    @NotNull
    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
