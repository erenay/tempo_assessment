import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A small TTL cache.
 *
 * The original bugs, in plain language, are listed in SimpleCacheReview.md.
 * This version fixes the ones that are local to the class:
 * - expired entries are deleted when they are read, and size() deletes them too
 * - the map stops accepting unlimited keys
 * - the deadline uses a monotonic clock, so a wall-clock jump cannot expire everything
 *
 * Two threads can still both miss, load a value, and the slower put() can
 * overwrite the newer one. Avoiding that needs a single in-flight load per key.
 * That is called out in the review and left out of this class on purpose.
 */
class SimpleCache<K : Any, V : Any>(
  private val ttlMs: Long = 60_000,
  private val maxSize: Int = 10_000,
  private val nowNanos: () -> Long = { System.nanoTime() },
) {
  init {
    require(ttlMs > 0)
    require(maxSize > 0)
  }

  private data class Entry<V>(val value: V, val savedAtNanos: Long)

  private val cache = ConcurrentHashMap<K, Entry<V>>()

  fun put(key: K, value: V) {
    cache[key] = Entry(value, nowNanos())
    if (cache.size > maxSize) {
      deleteExpired()
      while (cache.size > maxSize) {
        val extra = cache.keys.firstOrNull() ?: break
        cache.remove(extra)
      }
    }
  }

  fun get(key: K): V? {
    while (true) {
      val entry = cache[key] ?: return null
      if (!isExpired(entry)) return entry.value
      // remove(key, entry) deletes this entry only when it is still the one we read.
      // If it returns false, a newer put() replaced it, so we look again.
      if (cache.remove(key, entry)) return null
    }
  }

  /** Counts entries that have not expired yet. */
  fun size(): Int {
    deleteExpired()
    return cache.size
  }

  private fun isExpired(entry: Entry<V>): Boolean {
    val ageNanos = nowNanos() - entry.savedAtNanos
    return ageNanos >= ttlMs * 1_000_000
  }

  private fun deleteExpired() {
    for (key in cache.keys) {
      val entry = cache[key] ?: continue
      if (isExpired(entry)) cache.remove(key, entry)
    }
  }
}

class SimpleCacheTest {
  @Test
  fun `put then get`() {
    val cache = SimpleCache<String, String>(nowNanos = { 0L })
    cache.put("a", "alpha")
    assertEquals("alpha", cache.get("a"))
  }

  @Test
  fun `expired value is removed`() {
    var now = 0L
    val cache = SimpleCache<String, String>(ttlMs = 1_000, nowNanos = { now })
    cache.put("a", "alpha")

    now = 999L * 1_000_000
    assertEquals("alpha", cache.get("a"))

    now = 1_000L * 1_000_000
    assertNull(cache.get("a"))
    assertEquals(0, cache.size())
  }

  @Test
  fun `the map does not grow past maxSize`() {
    val cache = SimpleCache<Int, String>(maxSize = 3, nowNanos = { 0L })
    repeat(10) { cache.put(it, "v$it") }
    assertTrue(cache.size() <= 3)
  }
}
