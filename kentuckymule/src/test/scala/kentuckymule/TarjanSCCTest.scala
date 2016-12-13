package kentuckymule

import utest._

object TarjanSCCTest extends TestSuite {
  val alg = TarjanSCC
  val tests = this {
    'singleNode {
      val scc = alg.components[Symbol](Seq('a), _ => Set.empty)
      assert(scc == Seq(Set('a)))
    }
    // taken from https://en.wikipedia.org/wiki/Tarjan's_strongly_connected_components_algorithm
    'wikipediaExample {
      val nodes = Seq('v1, 'v2, 'v3, 'v4, 'v5, 'v6, 'v7, 'v8)
      val edges: Map[Symbol, Set[Symbol]] = Map(
        'v1 -> Set('v2),
        'v2 -> Set('v3),
        'v3 -> Set('v1),
        'v4 -> Set('v3, 'v5),
        'v5 -> Set('v4, 'v6),
        'v6 -> Set('v3, 'v7),
        'v7 -> Set('v6),
        'v8 -> Set('v7, 'v8)
      )
      val scc = alg.components[Symbol](nodes, edges)
      assert(scc.size == 4)
      //noinspection ZeroIndexToHead
      assert(scc(0) == Set('v1, 'v2, 'v3))
      assert(scc(1) == Set('v6, 'v7))
      assert(scc(2) == Set('v4, 'v5))
      assert(scc(3) == Set('v8))
    }
  }
}
