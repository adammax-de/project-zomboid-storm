package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.ContainerChainGuardMetrics;
import java.util.ArrayList;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;

/**
 * Cycle-safe replacements for the vanilla walks up the container chain ({@code ItemContainer}
 * &rarr; {@code containingItem} &rarr; {@code InventoryItem.getContainer()} &rarr; ...), behind
 * {@link ItemContainerChainGuardPatch} and {@link InventoryItemGetOutermostContainerPatch}.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>Vanilla assumes the chain is a tree. {@code ItemContainer.getCharacter()}, {@code
 * getOutermostContainer()}, {@code isInside()} and {@code isInCharacterInventory()} recurse through
 * it with no bound; {@code InventoryItem.getOutermostContainer()} spins a {@code while} loop over
 * it. Once a bag ends up inside its own inventory (or two bags inside each other) the recursive
 * walks throw {@code StackOverflowError} on the main thread and the {@code while} loop pins it at 0
 * TPS. {@code ItemContainer.AddItem(InventoryItem)} never checks for this, so a single malformed
 * transfer creates the cycle, and the next {@code Remove()} (which calls {@code getCharacter()}) or
 * the next periodic save (which descends the same links) takes the server down. Seen live as
 * thousands of {@code ItemContainer.getCharacter(ItemContainer.java)} frames in the server log.
 *
 * <h2>The fix</h2>
 *
 * <p>Every walk is bounded by {@link #MAX_DEPTH}. A chain that does not terminate within that many
 * hops is a cycle; {@link #repair} finds the container the walk revisits, detaches the item that
 * links into it (the item that was placed inside itself), logs it at WARN with enough detail to
 * restore it, and counts it. The walk then completes on the repaired chain. {@link #wouldNest}
 * refuses the {@code AddItem} that would create such a cycle in the first place.
 *
 * <p>Parameters are typed {@code Object} so the inlined advice does not embed checkcasts against
 * game classes into the patched method; the casts happen here.
 */
public final class ContainerChainGuard {

    /** Result marker for "the vanilla method would have returned {@code null}". */
    public static final Object NONE = new Object();

    /**
     * Real nesting never gets near this: bags cap at 50 capacity and lose weight reduction when
     * nested, so a chain deeper than a handful of hops is already a cycle.
     */
    static final int MAX_DEPTH = 64;

    private ContainerChainGuard() {}

    /** Replacement for {@code ItemContainer.getCharacter()}. Never returns {@code null}. */
    public static Object getCharacter(Object containerRef) {
        ItemContainer start = (ItemContainer) containerRef;
        ensureAcyclic(start);
        ItemContainer c = start;
        for (int hops = 0; c != null && hops <= MAX_DEPTH; hops++) {
            if (c.getParent() instanceof IsoGameCharacter chr) {
                return chr;
            }
            c = outer(c);
        }
        return NONE;
    }

    /**
     * Replacement for {@code ItemContainer.getOutermostContainer()}. Never returns {@code null}.
     */
    public static Object getOutermostContainer(Object containerRef) {
        ItemContainer start = (ItemContainer) containerRef;
        ensureAcyclic(start);
        ItemContainer c = start;
        for (int hops = 0; hops <= MAX_DEPTH; hops++) {
            ItemContainer next = outer(c);
            if (next == null) {
                return c;
            }
            c = next;
        }
        return start;
    }

    /**
     * Replacement for {@code InventoryItem.getOutermostContainer()}, which stops at a floor
     * container and returns {@code null} for an item that is not in a container. Never returns
     * {@code null}; {@link #NONE} stands in for it.
     */
    public static Object getOutermostContainerOfItem(Object itemRef) {
        InventoryItem item = (InventoryItem) itemRef;
        if (item.getContainer() != null) {
            ensureAcyclic(item.getContainer());
        }
        ItemContainer c = item.getContainer();
        if (c == null || "floor".equals(c.getType())) {
            return NONE;
        }
        for (int hops = 0; hops <= MAX_DEPTH; hops++) {
            ItemContainer next = outer(c);
            if (next == null || "floor".equals(next.getType())) {
                return c;
            }
            c = next;
        }
        return c;
    }

    /** Replacement for {@code ItemContainer.isInside(InventoryItem)}: 2 = true, 1 = false. */
    public static int isInside(Object containerRef, Object itemRef) {
        ItemContainer start = (ItemContainer) containerRef;
        ensureAcyclic(start);
        ItemContainer c = start;
        for (int hops = 0; c != null && hops <= MAX_DEPTH; hops++) {
            InventoryItem holder = c.getContainingItem();
            if (holder == null) {
                return 1;
            }
            if (holder == itemRef) {
                return 2;
            }
            c = holder.getContainer();
        }
        return 1;
    }

    /**
     * Replacement for {@code ItemContainer.isInCharacterInventory(IsoGameCharacter)}: 2 = true, 1 =
     * false.
     */
    public static int isInCharacterInventory(Object containerRef, Object characterRef) {
        ItemContainer start = (ItemContainer) containerRef;
        ItemContainer inventory = ((IsoGameCharacter) characterRef).getInventory();
        ensureAcyclic(start);
        ItemContainer c = start;
        for (int hops = 0; c != null && hops <= MAX_DEPTH; hops++) {
            if (c == inventory) {
                return 2;
            }
            InventoryItem holder = c.getContainingItem();
            if (holder == null) {
                return 1;
            }
            if (inventory.contains(holder, true)) {
                return 2;
            }
            c = holder.getContainer();
        }
        return 1;
    }

    /**
     * Gate for {@code ItemContainer.AddItem(InventoryItem)}: {@code true} when adding {@code item}
     * to {@code container} would put the item inside its own inventory, directly or through any
     * number of bags. Logs and counts the refusal.
     */
    public static boolean wouldNest(Object containerRef, Object itemRef) {
        if (!(itemRef instanceof InventoryContainer bag)) {
            return false;
        }
        ItemContainer target = (ItemContainer) containerRef;
        ensureAcyclic(target);
        ItemContainer c = target;
        for (int hops = 0; c != null && hops <= MAX_DEPTH; hops++) {
            if (c.getContainingItem() == bag) {
                ContainerChainGuardMetrics.recordRefusedNesting();
                LOGGER.warn(
                        "ContainerChainGuard: refused to put {} (id {}) inside itself via"
                                + " container [type {}, parent {}]",
                        bag.getFullType(),
                        bag.getID(),
                        target.getType(),
                        target.getParent());
                return true;
            }
            c = outer(c);
        }
        return false;
    }

    static ItemContainer outer(ItemContainer c) {
        InventoryItem holder = c.getContainingItem();
        return holder == null ? null : holder.getContainer();
    }

    /** Runs {@link #repair} when the chain above {@code start} does not end within the bound. */
    static void ensureAcyclic(ItemContainer start) {
        ItemContainer c = start;
        for (int hops = 0; hops <= MAX_DEPTH; hops++) {
            c = outer(c);
            if (c == null) {
                return;
            }
        }
        repair(start);
    }

    /**
     * Walks up from {@code start} remembering every container seen. The first container seen twice
     * closes the cycle; the item that links into it is detached from it. Returns {@code true} when
     * a link was cut.
     */
    static boolean repair(ItemContainer start) {
        ArrayList<ItemContainer> seen = new ArrayList<>();
        ItemContainer c = start;
        while (c != null) {
            for (int i = 0; i < seen.size(); i++) {
                if (seen.get(i) == c) {
                    return detach(seen.get(seen.size() - 1).getContainingItem(), c);
                }
            }
            seen.add(c);
            c = outer(c);
        }
        return false;
    }

    private static boolean detach(InventoryItem item, ItemContainer from) {
        if (item == null || item.getContainer() != from) {
            return false;
        }
        ArrayList<InventoryItem> items = from.getItems();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i) == item) {
                items.remove(i);
            }
        }
        item.setContainer(null);
        ContainerChainGuardMetrics.recordRepairedCycle();
        LOGGER.warn(
                "ContainerChainGuard: {} (id {}) was inside its own contents; detached it from"
                        + " container [type {}, parent {}] to break the cycle",
                item.getFullType(),
                item.getID(),
                from.getType(),
                from.getParent());
        return true;
    }
}
