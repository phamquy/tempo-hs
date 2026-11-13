/**
 * A {@code Hierarchy} stores an arbitrary <i>forest</i> (an ordered collection of ordered trees)
 * as an array of node IDs in the order of DFS traversal, combined with a parallel array of node depths.
 *
 * <p>Parent-child relationships are identified by the position in the array and the associated depth.
 * Each tree root has depth 0, its children have depth 1 and follow it in the array, their children have depth 2 and follow them, etc.
 *
 * <p>Example:
 * <pre>
 * nodeIds: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
 * depths:  0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2
 * </pre>
 *
 * <p>the forest can be visualized as follows:
 * <pre>
 * 1
 * - 2
 * - - 3
 * - - - 4
 * - 5
 * 6
 * - 7
 * 8
 * - 9
 * - 10
 * - - 11
 * </pre>
 * 1 is a parent of 2 and 5, 2 is a parent of 3, etc. Note that depth is equal to the number of hyphens for each node.
 *
 * <p>Invariants on the depths array:
 * <ul>
 *   <li>Depth of the first element is 0.</li>
 *   <li>If the depth of a node is {@code D}, the depth of the next node in the array can be:
 *     <ul>
 *       <li>{@code D + 1} if the next node is a child of this node;</li>
 *       <li>{@code D} if the next node is a sibling of this node;</li>
 *       <li>{@code d < D} - in this case the next node is not related to this node.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
interface Hierarchy {
    /** The number of nodes in the hierarchy. */
    int size();

    /**
     * Returns the unique ID of the node identified by the hierarchy index. The depth for this node will be {@code depth(index)}.
     * @param index must be non-negative and less than {@link #size()}
     */
    int nodeId(int index);

    /**
     * Returns the depth of the node identified by the hierarchy index. The unique ID for this node will be {@code nodeId(index)}.
     * @param index must be non-negative and less than {@link #size()}
     */
    int depth(int index);

    default String formatString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(nodeId(i)).append(":").append(depth(i));
        }
        sb.append("]");
        return sb.toString();
    }
}

/**
 * A node is present in the filtered hierarchy iff its node ID passes the predicate and all of its ancestors pass it as well.
 */
class HierarchyFilter {
    /**
     * Returns a new hierarchy containing only the nodes whose ID passes {@code nodeIdPredicate} and whose
     * ancestors all pass it. The input is never modified, and the result never shares arrays with it.
     *
     * <p>Assumptions:
     * <ul>
     *   <li>The input satisfies the invariants documented on {@link Hierarchy}; malformed input (for example a depth
     *       that jumps by more than one level) is not validated and yields unspecified output.</li>
     *   <li>The predicate is side-effect free and deterministic for a given ID. It is invoked at most once per node
     *       and never for descendants of a rejected node, so its call count is not part of any contract.</li>
     *   <li>Node IDs are unique, as stated on {@link Hierarchy#nodeId(int)}; the filter does not depend on it.</li>
     *   <li>Kept nodes retain their original depth, because a kept node always has all its ancestors kept.</li>
     *   <li>Relative order is preserved, so the result is again in DFS order and satisfies the invariants.</li>
     * </ul>
     *
     * <p>Complexity (n = {@code hierarchy.size()}):
     * <ul>
     *   <li>Time: O(n). Each node is visited exactly once in a single forward pass; skipping a rejected subtree is
     *       just a depth comparison per node, so no node is revisited. The predicate is called at most once per node
     *       (and never for descendants of a rejected node).</li>
     *   <li>Space: O(n) for the output arrays (allocated at size n, then trimmed to the kept count), and O(1) extra
     *       working state (one int for the rejected depth). No recursion or stack, so deep hierarchies are safe.</li>
     * </ul>
     *
     * @throws NullPointerException if {@code hierarchy} or {@code nodeIdPredicate} is null
     */
    public static Hierarchy filter(Hierarchy hierarchy, java.util.function.IntPredicate nodeIdPredicate) {
        java.util.Objects.requireNonNull(hierarchy, "hierarchy");
        java.util.Objects.requireNonNull(nodeIdPredicate, "nodeIdPredicate");
        int size = hierarchy.size();
        int[] ids = new int[size];
        int[] depths = new int[size];
        int count = 0;

        // Depth of the nearest rejected node whose subtree we are currently inside; -1 when not skipping.
        // Because the array is in DFS order, a rejected node's subtree is the run of following nodes with a greater depth.
        int rejectedDepth = -1;

        for (int i = 0; i < size; i++) {
            int depth = hierarchy.depth(i);
            if (rejectedDepth >= 0 && depth > rejectedDepth) {
                continue; // descendant of a rejected node; predicate is not even evaluated
            }
            rejectedDepth = -1;

            int id = hierarchy.nodeId(i);
            if (nodeIdPredicate.test(id)) {
                ids[count] = id;
                depths[count] = depth; // all ancestors are kept, so the depth is unchanged
                count++;
            } else {
                rejectedDepth = depth;
            }
        }

        if (count == size) {
            return new ArrayBasedHierarchy(ids, depths); // nothing removed: skip the trimming copy
        }
        return new ArrayBasedHierarchy(java.util.Arrays.copyOf(ids, count), java.util.Arrays.copyOf(depths, count));
    }
}

class ArrayBasedHierarchy implements Hierarchy {
    private final int[] nodeIds;
    private final int[] depths;

    public ArrayBasedHierarchy(int[] nodeIds, int[] depths) {
        this.nodeIds = nodeIds;
        this.depths = depths;
    }

    @Override
    public int size() {
        return depths.length;
    }

    @Override
    public int nodeId(int index) {
        return nodeIds[index];
    }

    @Override
    public int depth(int index) {
        return depths[index];
    }
}
