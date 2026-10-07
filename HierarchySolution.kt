import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A hierarchy is a forest stored in depth-first order.
 *
 *   ids:    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
 *   depths: 0, 1, 2, 3, 1, 0, 1, 0,  1,  1,  2
 *
 *   1
 *   - 2
 *   - - 3
 *   - - - 4
 *   - 5
 *   6
 *   - 7
 *   8
 *   - 9
 *   - 10
 *   - - 11
 *
 * filter() keeps a node when its id passes the test AND every ancestor passed too.
 * Children of a rejected node are dropped. Survivors keep the depth they already had.
 *
 * On the example above, `{ id % 3 != 0 }` removes 3, 6, and 9.
 * 4 goes away because it sits under 3. 7 goes away because it sits under 6.
 * 11 stays, because its ancestors are 8 and 10.
 * Result: 1:0, 2:1, 5:1, 8:0, 10:1, 11:2
 */

interface Hierarchy {
  val size: Int
  fun nodeId(index: Int): Int
  fun depth(index: Int): Int

  fun formatString(): String {
    return (0 until size).joinToString(prefix = "[", postfix = "]", separator = ", ") { i ->
      "${nodeId(i)}:${depth(i)}"
    }
  }
}

/**
 * One left-to-right pass.
 *
 * parentKept[d] answers: "was the current ancestor at depth d kept?"
 * The parent of a node at depth d is that flag at depth d - 1.
 * Roots have no parent, so they only need to pass the test themselves.
 *
 * This works because nodes are stored in depth-first order and the depth
 * grows by at most 1. When we later visit node 5 (depth 1), its parent is
 * still node 1, and parentKept[0] is still true. We simply overwrite
 * parentKept[1] with the decision for node 5.
 */
fun Hierarchy.filter(nodeIdPredicate: (Int) -> Boolean): Hierarchy {
  val keptIds = IntArray(size)
  val keptDepths = IntArray(size)
  val parentKept = BooleanArray(size)
  var kept = 0

  for (i in 0 until size) {
    val d = depth(i)
    val id = nodeId(i)
    val keepThis = (d == 0 || parentKept[d - 1]) && nodeIdPredicate(id)
    parentKept[d] = keepThis
    if (keepThis) {
      keptIds[kept] = id
      keptDepths[kept] = d
      kept++
    }
  }

  return ArrayBasedHierarchy(keptIds.copyOf(kept), keptDepths.copyOf(kept))
}

class ArrayBasedHierarchy(
  private val myNodeIds: IntArray,
  private val myDepths: IntArray,
) : Hierarchy {
  override val size: Int = myDepths.size
  override fun nodeId(index: Int): Int = myNodeIds[index]
  override fun depth(index: Int): Int = myDepths[index]
}

class FilterTest {
  @Test
  fun testFilter() {
    val unfiltered = ArrayBasedHierarchy(
      intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
      intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2),
    )
    val actual = unfiltered.filter { nodeId -> nodeId % 3 != 0 }
    val expected = ArrayBasedHierarchy(
      intArrayOf(1, 2, 5, 8, 10, 11),
      intArrayOf(0, 1, 1, 0, 1, 2),
    )
    assertEquals(expected.formatString(), actual.formatString())
  }

  @Test
  fun `empty input stays empty`() {
    val empty = ArrayBasedHierarchy(intArrayOf(), intArrayOf())
    assertEquals("[]", empty.filter { true }.formatString())
  }

  @Test
  fun `a rejected parent drops its children`() {
    // 1
    // - 2        rejected
    // - - 3      would pass, but its parent did not
    // - 4
    val tree = ArrayBasedHierarchy(
      intArrayOf(1, 2, 3, 4),
      intArrayOf(0, 1, 2, 1),
    )
    val actual = tree.filter { it != 2 }
    assertEquals("[1:0, 4:1]", actual.formatString())
  }

  @Test
  fun `a rejected root drops only its own tree`() {
    // 1
    // - 2
    // 3
    val forest = ArrayBasedHierarchy(
      intArrayOf(1, 2, 3),
      intArrayOf(0, 1, 0),
    )
    assertEquals("[3:0]", forest.filter { it != 1 }.formatString())
  }

  @Test
  fun `keeping every node returns the same hierarchy`() {
    val tree = ArrayBasedHierarchy(
      intArrayOf(1, 2, 3),
      intArrayOf(0, 1, 0),
    )
    assertEquals(tree.formatString(), tree.filter { true }.formatString())
  }
}
