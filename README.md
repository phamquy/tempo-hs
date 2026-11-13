## Running the tests

Requires Java 21 and Maven. 

Run `mvn test` from the repository root.

## Task 1: Hierarchy filter

The implementation is in [src/main/java/Hierarchy.java](src/main/java/Hierarchy.java) and the tests are in [src/test/java/FilterTest.java](src/test/java/FilterTest.java).

Because the hierarchy is stored in DFS order, the subtree of a rejected node is exactly the run of following nodes whose depth is greater than the rejected node's depth. The filter therefore makes one forward pass and remembers only the depth of the nearest rejected node, so it runs in O(n) time with no recursion and never evaluates the predicate for descendants of a rejected node. Kept nodes keep their original depth, since all of their ancestors are also kept.

Assumptions, which are also stated in the Javadoc of `filter()`:

- The input satisfies the documented depth invariants; malformed input is not validated and gives unspecified output.
- The predicate is pure and deterministic, and its call count is an optimisation rather than a contract.
- Node IDs are unique (as the interface states), though the filter does not rely on it.
- The input is never modified, and the result never shares arrays with it.
- A null hierarchy or null predicate throws `NullPointerException`.

The tests cover the given example, empty and single-node inputs, all and none passing, rejected roots, middle nodes and leaves, same-depth and multi-level pops after a rejection, nested rejections, negative and extreme IDs, a 100,000-deep chain, predicate call counts, input immutability, a custom `Hierarchy` implementation, and null arguments. A randomized test compares 2,000 generated forests against an independent reference implementation. Changing `depth > rejectedDepth` to `>=` makes 14 tests fail, which shows the suite detects boundary mistakes.

## Task 2: SimpleCache review

The review is in [SimpleCache.md](SimpleCache.md), ordered as must-fix, should-fix and minor. The headline problems are unbounded memory growth, a wall-clock-based expiry, and no atomic load-on-miss (a cache stampede).
