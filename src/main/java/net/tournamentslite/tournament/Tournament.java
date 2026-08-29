package net.tournamentslite.tournament;

import org.bukkit.Material;

import java.time.DayOfWeek;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All mutable state here is stored in thread-safe collections / volatile
 * fields because, on Folia, different players interacting with the same
 * tournament (signing up, an admin editing the leaderboard, /tl tpsigned)
 * can be ticking on completely different region threads at the same time.
 *
 * This class is a pure in-memory + config data holder - it never touches
 * the world/chunks/entities directly, so it never needs a region/entity
 * scheduler itself. Only the code that *uses* this data to affect players
 * (GuiListener, TournamentCommand) needs to worry about that.
 */
public class Tournament {

    private final String id;
    private volatile String displayName;
    private volatile int slot;
    private volatile Material material;
    // Optional distinct item icon used only for the pre-teleport animation
    // (see TeleportAnimator). If null, falls back to `material` above.
    private volatile Material animationItem;
    // Optional distinct item icon shown inside this tournament's sign-up/
    // leave confirmation Dialog popup (see ConfirmationDialogFactory). If
    // null, falls back to the global "gui.confirmation-dialog.icon-material"
    // setting in config.yml, which itself falls back to `material` above.
    private volatile Material dialogIcon;
    // Which day of the week this tournament recurs on. Purely informational
    // for the GUI countdown - you still decide exactly when to run it with
    // /tl tpsigned whenever you're ready.
    private volatile DayOfWeek weekday;

    // uuid -> sign up timestamp (millis)
    private final Map<UUID, Long> signups = new ConcurrentHashMap<>();

    // Manually entered leaderboard: rank (1-5) -> display name. This is
    // NOT derived from sign-ups or any tracked stat - you type the winners
    // in yourself, either by editing this tournament's file directly or
    // with /tl setwinner. Plain strings, not tied to a real player UUID,
    // since you may want to enter a name/team that isn't online right now.
    private final Map<Integer, String> leaderboardNames = new ConcurrentHashMap<>();

    public Tournament(String id, String displayName, int slot, Material material, DayOfWeek weekday) {
        this.id = id;
        this.displayName = displayName;
        this.slot = slot;
        this.material = material;
        this.weekday = weekday;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getSlot() {
        return slot;
    }

    public Material getMaterial() {
        return material;
    }

    /** The item shown in the pre-teleport animation - falls back to the GUI material if unset. */
    public Material getAnimationItem() {
        return animationItem != null ? animationItem : material;
    }

    /** Raw value (may be null), used when writing the tournament file back to disk. */
    public Material getAnimationItemRaw() {
        return animationItem;
    }

    public void setAnimationItem(Material animationItem) {
        this.animationItem = animationItem;
    }

    /** The item shown in this tournament's confirmation dialog - null means "use the global/config default". */
    public Material getDialogIcon() {
        return dialogIcon;
    }

    /** Raw value (may be null), used when writing the tournament file back to disk. */
    public Material getDialogIconRaw() {
        return dialogIcon;
    }

    public void setDialogIcon(Material dialogIcon) {
        this.dialogIcon = dialogIcon;
    }

    public DayOfWeek getWeekday() {
        return weekday;
    }

    public boolean isSignedUp(UUID uuid) {
        return signups.containsKey(uuid);
    }

    public boolean signUp(UUID uuid) {
        return signups.putIfAbsent(uuid, System.currentTimeMillis()) == null;
    }

    public boolean leave(UUID uuid) {
        return signups.remove(uuid) != null;
    }

    public int getSignupCount() {
        return signups.size();
    }

    public Map<UUID, Long> getSignups() {
        return signups;
    }

    public void clearSignups() {
        signups.clear();
    }

    // ---------------- manual leaderboard ----------------

    /** Returns the name entered for a rank (1-5), or empty string if unset. */
    public String getLeaderboardName(int rank) {
        return leaderboardNames.getOrDefault(rank, "");
    }

    public void setLeaderboardName(int rank, String name) {
        if (name == null || name.isBlank()) {
            leaderboardNames.remove(rank);
        } else {
            leaderboardNames.put(rank, name);
        }
    }

    public void clearLeaderboard() {
        leaderboardNames.clear();
    }

    /** Bulk-replace, used when reloading from disk. */
    public void replaceLeaderboard(Map<Integer, String> newNames) {
        leaderboardNames.clear();
        leaderboardNames.putAll(newNames);
    }

    public Map<Integer, String> getLeaderboardNames() {
        return leaderboardNames;
    }

    // ---- setters used by admin commands / config reload ----
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public void setMaterial(Material material) {
        this.material = material;
    }

    public void setWeekday(DayOfWeek weekday) {
        this.weekday = weekday;
    }
}
