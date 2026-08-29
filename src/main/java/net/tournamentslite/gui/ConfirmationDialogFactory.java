package net.tournamentslite.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.tournamentslite.TournamentsLitePlugin;
import net.tournamentslite.tournament.Tournament;
import net.tournamentslite.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the native Minecraft "Dialog" popup - the client-rendered menu
 * screen Mojang added in 1.21.6 (Paper's developer API for it landed in
 * 1.21.7) - shown when a player clicks a tournament item in the GUI, as
 * an alternative to the old "any click instantly signs you up / removes
 * you" behavior. This is a completely different UI layer from the
 * inventory GUI itself: it floats on top of whatever screen the player
 * currently has open and is closed automatically once they pick a button.
 *
 * Whether this is used at all is controlled by
 * "gui.confirmation-dialog.enabled" in config.yml (see GuiListener) -
 * this class only ever gets called once that's already been checked.
 *
 * Every piece of TEXT here - title, external title, body lines, both
 * button labels, both button tooltips, including any emoji you want in
 * them - comes straight out of messages.yml under "dialog.signup" /
 * "dialog.leave". Nothing is hardcoded, so re-wording or re-emoji-ing the
 * popup never requires touching code, just editing messages.yml and
 * running /tl reload.
 *
 * Folia note: everything below (reading config/messages, building a
 * Dialog object, calling Player#showDialog) only ever touches the one
 * player who clicked - the same "safe to run directly on whatever thread
 * already owns this player" reasoning GuiListener/TournamentGui already
 * rely on for InventoryClickEvent applies here too, since dialogs are
 * shown via the per-player Audience#showDialog call, not anything
 * region/world-scoped.
 */
public final class ConfirmationDialogFactory {

    private ConfirmationDialogFactory() {
    }

    /**
     * @param signingUp  true if confirming will sign the player UP, false if it will make them LEAVE.
     *                   Selects between the "dialog.signup" and "dialog.leave" sections of messages.yml.
     * @param onConfirm  run only if the player presses the confirm button. The cancel button (and Esc,
     *                   if allowed) simply closes the dialog and runs nothing.
     */
    public static Dialog build(TournamentsLitePlugin plugin, Player player, Tournament tournament,
                                boolean signingUp, Runnable onConfirm) {
        MessageUtil msg = plugin.getMessages();
        String base = signingUp ? "dialog.signup" : "dialog.leave";

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("display_name", tournament.getDisplayName());
        placeholders.put("player", player.getName());

        List<DialogBody> body = new ArrayList<>();
        Material icon = resolveIcon(plugin, tournament);
        if (icon != null) {
            boolean showDecorations = plugin.getConfig().getBoolean("gui.confirmation-dialog.show-item-decorations", true);
            boolean showTooltip = plugin.getConfig().getBoolean("gui.confirmation-dialog.show-item-tooltip", true);
            body.add(DialogBody.item(new ItemStack(icon))
                    .showDecorations(showDecorations)
                    .showTooltip(showTooltip)
                    .build());
        }
        for (Component line : msg.componentList(base + ".body", placeholders)) {
            body.add(DialogBody.plainMessage(line));
        }

        boolean escClose = plugin.getConfig().getBoolean("gui.confirmation-dialog.can-close-with-escape", true);

        DialogBase dialogBase = DialogBase.builder(msg.component(base + ".title", placeholders))
                .externalTitle(msg.component(base + ".external-title", placeholders))
                .canCloseWithEscape(escClose)
                .body(body)
                .build();

        ActionButton confirmButton = ActionButton.builder(msg.component(base + ".confirm-label", placeholders))
                .tooltip(msg.component(base + ".confirm-tooltip", placeholders))
                .action(DialogAction.customClick(
                        (view, audience) -> onConfirm.run(),
                        ClickCallback.Options.builder().uses(1).build()
                ))
                .build();

        ActionButton cancelButton = ActionButton.builder(msg.component(base + ".cancel-label", placeholders))
                .tooltip(msg.component(base + ".cancel-tooltip", placeholders))
                // No .action(...) call - a button with no action just closes the dialog and does nothing else.
                .build();

        return Dialog.create(builder -> builder.empty()
                .base(dialogBase)
                .type(DialogType.confirmation(confirmButton, cancelButton)));
    }

    /**
     * Works out which item to show inside the popup, in priority order:
     *   1. This tournament's own "dialog-icon:" (set in its file, e.g. tournaments/uhc.yml)
     *   2. The global "gui.confirmation-dialog.icon-material" in config.yml
     *      - the special value "TOURNAMENT_ICON" (the default) means "just reuse
     *        this tournament's normal GUI material", and "NONE" hides the icon entirely.
     *   3. Falls back to the tournament's normal GUI material if anything above is unset/invalid.
     */
    private static Material resolveIcon(TournamentsLitePlugin plugin, Tournament tournament) {
        Material perTournament = tournament.getDialogIconRaw();
        if (perTournament != null) return perTournament;

        String globalRaw = plugin.getConfig().getString("gui.confirmation-dialog.icon-material", "TOURNAMENT_ICON");
        if (globalRaw == null || globalRaw.isBlank() || globalRaw.equalsIgnoreCase("TOURNAMENT_ICON")) {
            return tournament.getMaterial();
        }
        if (globalRaw.equalsIgnoreCase("NONE")) {
            return null;
        }

        Material m = Material.matchMaterial(globalRaw.trim());
        return m != null ? m : tournament.getMaterial();
    }
}
