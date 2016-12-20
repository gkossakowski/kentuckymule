package kentuckymule

import utest._
import scala.collection.JavaConverters._

object TarjanSCCTest extends TestSuite {
  val alg = TarjanSCC
  val tests = this {
    'singleNode {
      val scc = alg.components[Symbol](Seq('a), _ => Set.empty)
      assert(scc.size == 1)
      assert(scc.get(0).vertices.asScala == Set('a))
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
      val sccResult = alg.collapsedGraph[Symbol](nodes, edges)
      val sccNodes = sccResult.components.asScala
      assert(sccNodes.size == 4)
      //noinspection ZeroIndexToHead
      assert(sccNodes(0).vertices.asScala == Set('v1, 'v2, 'v3))
      assert(sccNodes(1).vertices.asScala == Set('v6, 'v7))
      assert(sccNodes(2).vertices.asScala == Set('v4, 'v5))
      assert(sccNodes(3).vertices.asScala == Set('v8))
      val sccEdges = sccResult.edges
      val sccTargets = sccNodes.map(sccEdges.get(_).asScala.map(_.id))
      //noinspection ZeroIndexToHead
      assert(sccTargets(0).toSeq == Seq.empty)
      assert(sccTargets(1).toSeq == Seq(0))
      assert(sccTargets(2).toSeq == Seq(1, 0))
      assert(sccTargets(3).toSeq == Seq(1, 2))
    }
    'longestPathSingleNode {
      val scc = alg.collapsedGraph[Symbol](Seq('a), _ => Set.empty)
      val longestPath = alg.longestPath(scc)
      assert(longestPath.size == 1)
    }
    'longestPathWikipediaExample {
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
      val r@TarjanSCC.SCCResult(sccNodes, sccEdges) = alg.collapsedGraph[Symbol](nodes, edges)
      val longestPath = TarjanSCC.longestPath(r)
      assert(longestPath.length == 4)
    }
  }
}
