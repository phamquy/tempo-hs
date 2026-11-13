import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilterTest {
    private static Hierarchy h(int[] ids, int[] depths) {
        return new ArrayBasedHierarchy(ids, depths);
    }

    private static Hierarchy empty() {
        return h(new int[0], new int[0]);
    }

    private static void assertFiltered(Hierarchy expected, Hierarchy actual) {
        assertEquals(expected.formatString(), actual.formatString());
    }

    @Test
    void testFilter() {
        Hierarchy unfiltered = h(
            new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
            new int[]{0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2}
        );
        Hierarchy filteredActual = HierarchyFilter.filter(unfiltered, nodeId -> nodeId % 3 != 0);
        Hierarchy filteredExpected = h(
            new int[]{1, 2, 5, 8, 10, 11},
            new int[]{0, 1, 1, 0, 1, 2}
        );
        assertFiltered(filteredExpected, filteredActual);
    }

    @Test
    void emptyHierarchy() {
        assertFiltered(empty(), HierarchyFilter.filter(empty(), id -> true));
    }

    @Test
    void allPass() {
        Hierarchy src = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 2, 0});
        assertFiltered(src, HierarchyFilter.filter(src, id -> true));
    }

    @Test
    void nonePass() {
        Hierarchy src = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 2, 0});
        assertFiltered(empty(), HierarchyFilter.filter(src, id -> false));
    }

    @Test
    void rejectedRootRemovesWholeTreeEvenIfDescendantsPass() {
        Hierarchy src = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 2, 0, 1});
        assertFiltered(h(new int[]{4, 5}, new int[]{0, 1}), HierarchyFilter.filter(src, id -> id != 1));
    }

    @Test
    void rejectedLeafOnly() {
        Hierarchy src = h(new int[]{1, 2, 3}, new int[]{0, 1, 1});
        assertFiltered(h(new int[]{1, 3}, new int[]{0, 1}), HierarchyFilter.filter(src, id -> id != 2));
    }

    @Test
    void rejectedMiddleNodeRemovesSubtreeButKeepsSiblingsAndLaterTrees() {
        Hierarchy src = h(new int[]{1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 3, 1, 0});
        assertFiltered(h(new int[]{1, 5, 6}, new int[]{0, 1, 0}), HierarchyFilter.filter(src, id -> id != 2));
    }

    @Test
    void consecutiveRejectedSiblingsAtSameDepth() {
        Hierarchy src = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 1, 2, 1});
        assertFiltered(h(new int[]{1, 5}, new int[]{0, 1}), HierarchyFilter.filter(src, id -> id == 1 || id == 5));
    }

    @Test
    void rejectedNodeFollowedByShallowerNodeResumesOutput() {
        // 1 / - 2 / - - 3 / 4 : rejecting 3 must not swallow 4 (depth drops to 0)
        Hierarchy src = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 2, 0});
        assertFiltered(h(new int[]{1, 2, 4}, new int[]{0, 1, 0}), HierarchyFilter.filter(src, id -> id != 3));
    }

    @Test
    void rejectedDeepNodeFollowedByNodeSeveralLevelsShallower() {
        // 1 / - 2 / - - 3 / - - - 4 / - 5 : rejecting 4 (depth 3) then popping two levels to 5 (depth 1)
        Hierarchy src = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 2, 3, 1});
        assertFiltered(h(new int[]{1, 2, 3, 5}, new int[]{0, 1, 2, 1}), HierarchyFilter.filter(src, id -> id != 4));
    }

    @Test
    void rejectedNodeFollowedByPassingSiblingAtSameDepth() {
        // the sibling has depth == rejectedDepth, so it is NOT a descendant and must be evaluated
        Hierarchy src = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 2, 2});
        assertFiltered(h(new int[]{1, 2, 4}, new int[]{0, 1, 2}), HierarchyFilter.filter(src, id -> id != 3));
    }

    @Test
    void rejectedRootFollowedByPassingRootAtDepthZero() {
        // depth 0 is the boundary of the "no rejection active" sentinel (-1) and must not be confused with it
        Hierarchy src = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 0, 1});
        assertFiltered(h(new int[]{3, 4}, new int[]{0, 1}), HierarchyFilter.filter(src, id -> id != 1));
    }

    @Test
    void nestedRejectionInsideKeptSubtree() {
        // rejecting an inner node twice in different subtrees; the marker must reset between them
        Hierarchy src = h(new int[]{1, 2, 3, 4, 5, 6, 7}, new int[]{0, 1, 2, 1, 2, 3, 2});
        assertFiltered(h(new int[]{1, 4, 7}, new int[]{0, 1, 2}), HierarchyFilter.filter(src, id -> id != 2 && id != 5));
    }

    @Test
    void rejectedAncestorAndRejectedDescendantBothFailPredicate() {
        // the descendant also fails, but must be skipped without overwriting the outer rejected depth
        Hierarchy src = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 2, 2, 1});
        assertFiltered(h(new int[]{1, 5}, new int[]{0, 1}), HierarchyFilter.filter(src, id -> id == 1 || id == 5));
    }

    @Test
    void forestOfSingleNodeTrees() {
        Hierarchy src = h(new int[]{1, 2, 3, 4}, new int[]{0, 0, 0, 0});
        assertFiltered(h(new int[]{1, 3}, new int[]{0, 0}), HierarchyFilter.filter(src, id -> id % 2 == 1));
    }

    @Test
    void singleNode() {
        Hierarchy src = h(new int[]{7}, new int[]{0});
        assertFiltered(src, HierarchyFilter.filter(src, id -> id == 7));
        assertFiltered(empty(), HierarchyFilter.filter(src, id -> id != 7));
    }

    @Test
    void negativeAndExtremeNodeIds() {
        Hierarchy src = h(new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}, new int[]{0, 1, 1, 0});
        assertFiltered(
            h(new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, new int[]{0, 1, 0}),
            HierarchyFilter.filter(src, id -> id != -1));
    }

    @Test
    void deepChain() {
        int n = 100_000;
        int[] ids = new int[n];
        int[] depths = new int[n];
        for (int i = 0; i < n; i++) { ids[i] = i; depths[i] = i; }
        Hierarchy result = HierarchyFilter.filter(h(ids, depths), id -> id < 50_000);
        assertEquals(50_000, result.size());
        assertEquals(49_999, result.depth(49_999));
    }

    @Test
    void predicateNotEvaluatedForDescendantsOfRejectedNodes() {
        Hierarchy src = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 2, 0});
        AtomicInteger calls = new AtomicInteger();
        HierarchyFilter.filter(src, id -> { calls.incrementAndGet(); return id != 1; });
        assertEquals(2, calls.get()); // nodes 1 and 4 only
    }

    @Test
    void predicateCalledAtMostOncePerNode() {
        Hierarchy src = h(new int[]{1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 1, 0, 1});
        List<Integer> seen = new ArrayList<>();
        HierarchyFilter.filter(src, id -> { seen.add(id); return id % 2 == 0; });
        assertEquals(List.of(1, 5), seen); // 1 rejected -> its subtree skipped; 5 rejected -> 6 skipped
    }

    @Test
    void inputIsNotModified() {
        int[] ids = {1, 2, 3};
        int[] depths = {0, 1, 0};
        Hierarchy src = h(ids, depths);
        HierarchyFilter.filter(src, id -> id != 2);
        assertEquals("[1:0, 2:1, 3:0]", src.formatString());
        assertEquals(List.of(1, 2, 3), List.of(ids[0], ids[1], ids[2]));
        assertEquals(List.of(0, 1, 0), List.of(depths[0], depths[1], depths[2]));
    }

    @Test
    void resultDoesNotShareStateWithInputWhenNothingIsRemoved() {
        Hierarchy src = h(new int[]{1, 2}, new int[]{0, 1});
        Hierarchy result = HierarchyFilter.filter(src, id -> true);
        assertNotSame(src, result);
        assertFiltered(src, result);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(null, id -> true));
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(empty(), null));
    }

    @Test
    void worksWithCustomHierarchyImplementation() {
        // filter must rely only on the Hierarchy interface, not on ArrayBasedHierarchy
        Hierarchy custom = new Hierarchy() {
            public int size() { return 3; }
            public int nodeId(int i) { return 10 + i; }
            public int depth(int i) { return i == 2 ? 0 : i; }
        };
        assertFiltered(h(new int[]{10, 12}, new int[]{0, 0}), HierarchyFilter.filter(custom, id -> id != 11));
    }

    /**
     * Reference implementation used as an oracle: a node is kept iff its parent was kept and it passes the predicate.
     * Tracks "was the latest node at each depth kept" instead of a rejected-depth marker, so it is independent of
     * the production logic. The predicate is evaluated for every node here (unlike production), which is fine for
     * a pure predicate.
     */
    private static List<int[]> naive(Hierarchy src, IntPredicate p) {
        List<int[]> out = new ArrayList<>();
        boolean[] keptAtDepth = new boolean[src.size() + 1];
        for (int i = 0; i < src.size(); i++) {
            int d = src.depth(i);
            boolean ancestorsKept = d == 0 || keptAtDepth[d - 1];
            boolean kept = ancestorsKept && p.test(src.nodeId(i));
            keptAtDepth[d] = kept;
            if (kept) out.add(new int[]{src.nodeId(i), d});
        }
        return out;
    }

    private static Hierarchy randomHierarchy(Random rnd, int n) {
        int[] ids = new int[n];
        int[] depths = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            // valid next depth: anywhere from 0 up to previous depth + 1; the first node is a root
            depths[i] = i == 0 ? 0 : rnd.nextInt(depths[i - 1] + 2);
        }
        return h(ids, depths);
    }

    @Test
    void randomizedAgainstReferenceImplementation() {
        Random rnd = new Random(42);
        for (int iter = 0; iter < 2_000; iter++) {
            int n = rnd.nextInt(40);
            Hierarchy src = randomHierarchy(rnd, n);
            int mod = 2 + rnd.nextInt(4);
            int rem = rnd.nextInt(mod);
            IntPredicate p = id -> id % mod != rem;

            List<int[]> expected = naive(src, p);
            Hierarchy actual = HierarchyFilter.filter(src, p);

            assertEquals(expected.size(), actual.size(), "size mismatch for " + src.formatString());
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i)[0], actual.nodeId(i), "id mismatch for " + src.formatString());
                assertEquals(expected.get(i)[1], actual.depth(i), "depth mismatch for " + src.formatString());
            }
        }
    }
}
