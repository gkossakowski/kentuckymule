package kentuckymule

import java.util
import scala.collection.mutable

/**
  * Tarjan's algorithm for finding Strongly Connected Components
  */
object TarjanSCC {
  def components[T >: Null](allNodes: Iterable[T], edges: T => Iterable[T]): Seq[Set[T]] = {
    val alg = new TarjanSCC[T](allNodes, edges)
    alg.components()
  }
}

private class TarjanSCC[T >: Null](nodes: Iterable[T], edges: T => Iterable[T]) {
  private var curIndex: Int = 0
  private case class VertexData(vertex: T, index: Int, var lowLink: Int)
  private object stack {
    private val underlying: util.Stack[VertexData] = new util.Stack[VertexData]()
    private val onStack: mutable.Set[T] = mutable.Set.empty[T]
    def push(data: VertexData): Unit = {
      underlying.push(data)
      onStack += data.vertex
    }
    def pop(): VertexData = {
      val data = underlying.pop()
      onStack -= data.vertex
      data
    }
    def contains(v: T): Boolean = onStack contains v

  }
  private val vertexData: mutable.Map[T, VertexData] = mutable.Map.empty
  private val collectedComponents: mutable.Buffer[Set[T]] = mutable.Buffer.empty

  def components(): Seq[Set[T]] = {
    for (v <- nodes)
      if (!visited(v))
        strongConnect(v)
    collectedComponents
  }

  private def nextIndex(): Int = {
    val r = curIndex
    curIndex += 1
    r
  }
  private def visited(v: T): Boolean = vertexData contains v
  private def strongConnect(v: T): Unit = {
    val index = nextIndex()
    val vData = VertexData(v, index = index, lowLink =  index)
    vertexData(v) = vData
    stack.push(vData)
    val vEdges = edges(v)
    for (w <- vEdges) {
      if (!visited(w)) {
        strongConnect(w)
        val wData = vertexData(w)
        vData.lowLink = math.min(vData.lowLink, wData.lowLink)
      } else if (stack contains w) {
        vData.lowLink = math.min(vData.lowLink, vertexData(w).index)
      }
    }

    if (vData.lowLink == vData.index) {
      val buf = mutable.Buffer.empty[T]
      var w: T = null
      do {
        w = stack.pop().vertex
        buf += w
      } while (w != v)
      collectedComponents += buf.toSet
    }
  }
}
