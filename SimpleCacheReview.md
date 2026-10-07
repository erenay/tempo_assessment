# SimpleCache review

The map itself is a reasonable choice: many reads, fewer writes, tens of threads. `CacheEntry` is immutable, so one `get` cannot see a value paired with the wrong timestamp.

The problems below should be fixed before this runs in production. A smaller fixed version is in `SimpleCache.kt`.

## 1. Expired entries are never deleted

`get` returns null after 60 seconds and leaves the entry in the map. Nothing else walks the map to clean it up. Every distinct key ever written stays until the process dies.

At a few hundred writes per second that grows the heap, then the process runs out of memory. Reads of an expired hot key also keep paying for a lookup that can never succeed.

Fix: when `get` sees an expired entry, remove that entry. `remove(key, entry)` is the safe call: it deletes the entry only if it is still the one we just read, so a newer `put` is left alone.

## 2. There is no size limit

Even fresh entries can fill the heap. A cache needs a maximum number of keys. When the map is past that limit, drop expired entries first, then drop extra keys until it fits. For real least-recently-used eviction, use a library such as Caffeine.

## 3. `size()` counts dead entries

`cache.size` includes entries that `get` will no longer return. Metrics then show a healthy cache while most of it is already expired.

Fix: delete expired entries before reporting the size, or keep a separate count of live entries.

## 4. `get` can miss a value that was just saved

1. Thread A reads an expired entry.
2. Thread B saves a new value for the same key.
3. Thread A decides its old copy is expired and returns null.

The new value is in the map, and the caller still treats it as a miss. Around the 60-second mark a popular key sends a burst of loads to the database.

Fix: if the expired entry was already replaced, read the key again instead of returning null.

## 5. A slow save can overwrite a newer value

1. Thread A starts a slow load and will save `v1`.
2. Thread B loads `v2` and saves it.
3. Thread A finishes and saves `v1` on top.

The cache serves the older value for a full minute. The usual fix is one load per key at a time: other threads wait for that load instead of starting their own. `SimpleCache.kt` does not include that piece; it is the part that made the previous version hard to read.

## 6. The clock can jump

`System.currentTimeMillis()` follows wall time. If the clock steps backward, entries live too long. If it steps forward, every entry expires together and every thread misses at once.

Store the save time with `System.nanoTime()` and measure age from that. `nanoTime` does not jump when the wall clock is corrected.

## 7. Smaller issues

- `K` and `V` allow null, and `ConcurrentHashMap` throws `NullPointerException` on a null key or value. Declare `K : Any` and `V : Any`.
- If a caller mutates the cached object after `put`, other threads can see that change with no locking. Store immutable values.
- There is no way to drop one key early. A row deleted in the database is still served for up to 60 seconds. Add `invalidate(key)`.
