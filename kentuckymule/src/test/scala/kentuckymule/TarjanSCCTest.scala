package kentuckymule

import utest._

object TarjanSCCTest extends TestSuite {
  val alg = TarjanSCC
  val tests = this {
    'singleNode {
      val scc = alg.components[Symbol](Seq('a), _ => Set.empty)
      assert(scc.size == 1)
      assert(scc(0).vertices == Set('a))
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
        'v8 -> Set('v5, 'v7, 'v8)
      )
      val TarjanSCC.SCCResult(sccNodes, sccEdges) = alg.collapsedGraph[Symbol](nodes, edges)
      assert(sccNodes.size == 4)
      //noinspection ZeroIndexToHead
      assert(sccNodes(0).vertices == Set('v1, 'v2, 'v3))
      assert(sccNodes(1).vertices == Set('v6, 'v7))
      assert(sccNodes(2).vertices == Set('v4, 'v5))
      assert(sccNodes(3).vertices == Set('v8))
      val sccTargets = sccNodes.map(node => sccEdges(node).map(_.id))
      //noinspection ZeroIndexToHead
      assert(sccTargets(0).toSeq == Seq.empty)
      assert(sccTargets(1).toSeq == Seq(0))
      assert(sccTargets(2).toSeq == Seq(1, 0))
      assert(sccTargets(3).toSeq == Seq(1, 2))
    }
  }
}
