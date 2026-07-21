package net.tournamentslite.teleport;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DeathProtection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.tournamentslite.TournamentsLitePlugin;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;

/**
 * Plays a short, fully private pre-teleport animation for one player, then
 * teleports them once it finishes.
 *
 * How the totem-pop screen flash is made to show a CUSTOM icon (cobweb,
 * sword, TNT, etc.) instead of a plain totem:
 *   Since Minecraft 1.21.2, any item can act as a totem via the
 *   DataComponentTypes.DEATH_PROTECTION data component - that's the same
 *   generalization vanilla uses so resource packs / data packs can make
 *   non-totem items save your life. Player#playEffect(EntityEffect
 *   .PROTECTED_FROM_DEATH) (the current, non-deprecated replacement for
 *   the old TOTEM_RESURRECT id) tells the client "play the totem flash";
 *   per Paper's own docs, the client shows whichever item it currently
 *   sees equipped that carries that component, falling back to a plain
 *   totem if none is found. So we briefly place a copy of the tournament's
 *   icon item (tagged with that component) into the player's OFF HAND,
 *   trigger the flash, then restore their real off-hand item a couple of
 *   ticks later - short enough that nothing is visibly different in their
 *   own UI, since the big full-screen flash happens at the same instant.
 *
 * How the whole animation stays PRIVATE (nobody else ever sees it):
 *   The client only renders that dramatic full-screen flash when the
 *   entity referenced in the packet is the client's OWN player - that
 *   check is hardcoded client-side, so the flash itself is never drawn on
 *   anyone else's screen no matter what. The sound and the extra floating
 *   icon particle both use the Player-scoped overloads
 *   (Player#playSound(Player, ...), Player#spawnParticle(...)), which send
 *   the packet directly to that one player's connection only - never
 *   broadcast to nearby observers. The one-tick off-hand swap is likewise
 *   only ever synced to the player's own client (equipment updates for a
 *   player's own hands are part of their own inventory state).
 *
 * Folia note: the delayed "swap the off-hand item back" and "now actually
 * teleport" steps are both scheduled on the PLAYER'S OWN EntityScheduler
 * (Player#getScheduler()), not the global or a region scheduler. That ties
 * each delayed task to this specific player's lifecycle - if they log out
 * mid-animation, the "retired" callback fires instead of the task, so we
 * never touch a stale/removed entity. In the extremely unlikely case a
 * player disconnects in the ~100ms window between the off-hand swap and
 * its revert, the worst outcome is they simply keep one plain, unenchanted
 * copy of the icon item (cobweb/sword/etc.) in their off-hand - harmless,
 * not exploitable, and not worth adding extra complexity to fully close.
 */
public final class TeleportAnimator {

    private static final long ICON_REVERT_DELAY_TICKS = 4L;

    private TeleportAnimator() {
    }

    public static void animateThenTeleport(TournamentsLitePlugin plugin, Player target,
                                            Location destination, Material animationItem, long delayTicks) {
        if (!target.isOnline()) return;

        applyTemporaryTotemIcon(plugin, target, animationItem);

        // Totem pop sound - Player#playSound only ever reaches this one
        // connection, never broadcast.
        target.playSound(target.getLocation(), Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1.0f, 1.0f);

        // A themed icon lingering in front of the player's own view for a
        // moment after the flash - Player#spawnParticle is likewise
        // private-only.
        Location particleLoc = target.getEyeLocation().add(target.getEyeLocation().getDirection().multiply(1.3));
        target.spawnParticle(Particle.ITEM, particleLoc, 14, 0.2, 0.2, 0.2, 0.03, new ItemStack(animationItem));

        // Small private title card so it's obvious what's happening even
        // for players not looking at their held item / particles.
        Title title = Title.title(
                Component.text("Teleporting...", NamedTextColor.AQUA),
                Component.text("Get ready!", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(ticksToMillis(delayTicks)), Duration.ofMillis(200))
        );
        target.showTitle(title);

        // After the animation has had time to play, actually teleport -
        // scheduled on this player's own EntityScheduler so it safely
        // no-ops if they disconnect in the meantime.
        target.getScheduler().runDelayed(plugin,
                scheduledTask -> {
                    if (target.isOnline()) {
                        target.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.COMMAND);
                    }
                },
                () -> { /* player/entity was removed before this ran - nothing to do */ },
                Math.max(1L, delayTicks));
    }

    /**
     * Briefly tags a copy of {@code icon} with the DEATH_PROTECTION
     * component and puts it in the player's off-hand so the totem flash
     * shows that item, then reverts their real off-hand item shortly after.
     */
    private static void applyTemporaryTotemIcon(TournamentsLitePlugin plugin, Player target, Material icon) {
        ItemStack originalOffHand = target.getInventory().getItemInOffHand().clone();

        ItemStack fakeIcon = new ItemStack(icon);
        // Empty death-effects list is fine here: playEffect() only sends the
        // cosmetic packet, it never runs the real "consume totem" logic, so
        // this list (which would only matter for an actual death save) is
        // never read.
        fakeIcon.setData(DataComponentTypes.DEATH_PROTECTION, DeathProtection.deathProtection(List.of()));
        target.getInventory().setItemInOffHand(fakeIcon);

        target.playEffect(EntityEffect.PROTECTED_FROM_DEATH);

        target.getScheduler().runDelayed(plugin,
                scheduledTask -> {
                    if (target.isOnline()) {
                        target.getInventory().setItemInOffHand(originalOffHand);
                    }
                },
                () -> { /* entity removed before we could restore - see class javadoc */ },
                ICON_REVERT_DELAY_TICKS);
    }

    private static long ticksToMillis(long ticks) {
        return ticks * 50L;
    }
}
