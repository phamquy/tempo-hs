## Code Review

You are reviewing the following code submitted as part of a task to implement an item cache in a highly concurrent application. The anticipated load includes: thousands of reads per second, hundreds of writes per second, tens of concurrent threads.
Your objective is to identify and explain the issues in the implementation that must be addressed before deploying the code to production. Please provide a clear explanation of each issue and its potential impact on production behaviour.

```java
import java.util.concurrent.ConcurrentHashMap;

public class SimpleCache<K, V> {
    private final ConcurrentHashMap<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final long ttlMs = 60000; // 1 minute

    public static class CacheEntry<V> {
        private final V value;
        private final long timestamp;

        public CacheEntry(V value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public V getValue() {
            return value;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    public void put(K key, V value) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() - entry.getTimestamp() < ttlMs) {
                return entry.getValue();
            }
        }
        return null;
    }

    public int size() {
        return cache.size();
    }
}
```

## Review

**Summary.** The class is thread-safe in the narrow sense that `ConcurrentHashMap` will not corrupt itself, but it is not production-ready: it leaks memory without bound, its expiry depends on a clock that can move backwards, and it gives callers no safe way to populate it under load. Issues 1 to 3 would cause incidents on their own; the rest are correctness, operability and API gaps. The simplest remediation is to replace the class with a library cache such as Caffeine (`maximumSize`, `expireAfterWrite`, `Cache.get(key, loader)`, `recordStats`), which fixes every item below; if a hand-rolled cache is a hard requirement, the fixes are listed per item.

### Must fix before production

1. **Unbounded memory growth (no eviction).** `get()` treats an expired entry as a miss but never removes it, and nothing else removes entries either. Every key ever written stays in the map forever unless it is overwritten. With hundreds of writes per second and a high-cardinality key space, the heap grows monotonically until GC thrashes and the JVM dies with `OutOfMemoryError`. Impact: slow leak that surfaces as a production outage days later. Fix: bound the cache (maximum size with LRU or TinyLFU eviction) and remove expired entries, either on read with `cache.remove(key, entry)` or with a periodic sweeper on a `ScheduledExecutorService`.
2. **`size()` is wrong and expiry is only lazy.** `size()` counts expired entries, so it reports a number that has no relation to the number of usable items, which breaks any dashboard, alert or capacity decision built on it. `ConcurrentHashMap.size()` is also only an estimate under concurrent updates. Fix: evict proactively (item 1) and document `size()` as approximate, or expose separate live/expired counts.

   Example:

   - At t=0 you `put` 1,000 keys, and `size()` returns 1000.

   * At t=61s every one of those entries is expired. `get()` returns `null` for all of them.
   * `size()` still returns 1000, even though the cache can serve 0 items.
3. **Wall-clock time is used for expiry.** `System.currentTimeMillis()` is not monotonic: NTP corrections, VM pause/resume or manual clock changes can make `now - timestamp` negative (entry never expires, so stale data is served indefinitely) or jump forward (entries expire early, causing a miss storm). Fix: use `System.nanoTime()` for elapsed-time measurement, or inject a `Clock`/`Ticker` so it is also testable.
4. **No atomic load-on-miss, so a cache stampede is possible.** The API is `get()` returning `null` plus a separate `put()`. When a hot key expires, every one of the tens of concurrent threads sees a miss at once and each hits the backend (the thundering herd), then each overwrites the others. At thousands of reads per second on a popular key this can overload the database exactly when the cache was supposed to protect it. Fix: provide `get(key, loader)` built on `ConcurrentHashMap.compute`/`computeIfAbsent` (or `CompletableFuture` values) so only one thread loads per key, and consider refresh-ahead or jittered TTLs. A related flaw: the timestamp is taken inside `put()`, after a slow load has finished, so a value that was already old when the load started gets a full fresh TTL.
5. **Null handling is broken and ambiguous.** `put(null, v)`, `put(k, null)` and `get(null)` throw `NullPointerException` from `ConcurrentHashMap`, with no documentation or validation message. Separately, `get()` returns `null` both for "not cached" and "expired", so callers cannot tell a miss from a legitimately cached absent value, which prevents negative caching and makes callers re-query for missing items every time. Fix: validate arguments explicitly, return `Optional<V>` or a sentinel, and decide on a negative-caching policy.

### Should fix

6. **TTL is hard-coded.** `ttlMs` is a private constant of one minute: no constructor parameter, no per-entry TTL, no configuration per environment. Different data has different freshness needs, and tests must wait a real minute to exercise expiry. Fix: take the TTL (and a clock) in the constructor.
7. **Expiry races: check-then-act and non-atomic expiry on read.** `get()` reads the entry, compares the clock, and returns, with no atomicity between the check and anything that follows. Today this is harmless, but it constrains the fix for item 1: the obvious patch `if (expired) cache.remove(key)` would race, because between the expiry check and the removal another thread can `put` a fresh value that then gets deleted, so a just-written entry vanishes and causes a spurious miss and extra backend load. Any eviction added to this class must use the two-argument `cache.remove(key, entry)` (or `computeIfPresent`) so only the exact stale entry is removed.
8. **No invalidation or lifecycle API.** There is no `remove(key)`, `clear()` or bulk invalidation, so when the source of truth changes the only option is to wait up to a minute for entries to age out (and, per item 1, they are never actually freed). Stale reads after an update are guaranteed for the TTL window with no escape hatch.
9. **No observability.** There are no hit/miss/eviction/load counters, so hit ratio, eviction rate and load latency cannot be monitored or tuned in production. Fix: add counters (or `LongAdder`s, which scale better than `AtomicLong` under this contention) and export them as metrics.

### Minor

10. **Leaky surface and mutable cached values.** `CacheEntry` is a public nested class that exposes internals callers do not need, and the class is not `final`, so it can be subclassed and its contract weakened. The entry itself is safely published (both fields are `final`), but the cached value is stored by reference: if `V` is mutable, a caller that mutates a returned object changes it for every other thread with no synchronisation, which is a data race on the value itself and can surface as torn or inconsistent reads. Cache immutable values (or defensive copies) and document that requirement. Make the entry private (or a `record`) and the class `final`.
11. **Per-write allocation.** Each `put` allocates a new `CacheEntry`. At hundreds of writes per second this is negligible, so it is not worth optimising, but it is one more reason to prefer a purpose-built cache if throughput grows.
12. **TTL starts at write, not at last access, and there is no refresh.** The timestamp is set only in `put()`, so a hot key is guaranteed to expire one minute after it was written even if it is read thousands of times per second, producing a periodic synchronised miss for the most valuable entries (this is what triggers the stampede in item 4). If sliding expiry is wanted it must be explicit; otherwise add refresh-ahead or jitter.
13. **Key contract is undocumented.** Correctness depends on `K` having a consistent `equals`/`hashCode`, and `K` must be immutable: a key mutated after insertion becomes unreachable and permanently leaks (compounding item 1). Nothing in the class states or enforces this.
14. **No tests.** Nothing verifies expiry, concurrency behaviour or the null cases; items 3 and 6 make the current design hard to test deterministically.
