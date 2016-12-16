package kentuckymule

import java.util
import java.util.Collections

import com.google.common.collect.ImmutableMultimap

import scala.collection.mutable

/**
  * Tarjan's algorithm for finding Strongly Connected Components
  *
  * See documentation in the companion class for implementation details.
  */
object TarjanSCC {
  case class Component[T](id: Int)(val vertices: Set[T])
  case class SCCResult[T](components: Seq[Component[T]], edges: ImmutableMultimap[Component[T], Component[T]])
  def components[T >: Null](allNodes: Iterable[T], edges: T => Iterable[T]): Seq[Component[T]] = {
    val alg = new TarjanSCC[T](allNodes, edges)
    alg.run().components
  }
  def collapsedGraph[T >: Null](allNodes: Iterable[T], edges: T => Iterable[T]): SCCResult[T] = {
    val alg = new TarjanSCC[T](allNodes, edges)
    alg.run()
  }

  /**
    * Returns the longest path in a DAG returned by `collapsedGraph` method.
    *
    * The implementation is optimized for speed so it doesn't use any higher-order
    * method on collections.
    * @param sccResult
    * @tparam T
    * @return
    */
  def longestPath[T](sccResult: SCCResult[T]): Seq[Component[T]] = {
    // Tarjan returns components in the reverse topological order
    val topoOrder = sccResult.components.reverse
    val size = topoOrder.length
    if (size == 0)
      return Seq.empty
    val reverseEdges = sccResult.edges.inverse()
    val longestPath: Array[Int] = Array.ofDim(size)
    val longestPathOrigin: Array[Component[T]] = Array.ofDim(size)
    var i = 0
    while (i < size) {
      val node = topoOrder(i)
      val incomingNeighbors = reverseEdges.get(node)
      if (incomingNeighbors.isEmpty) {
        longestPath(node.id) = 0
        // make it explicit that the node with no incoming edges has null as origin
        longestPathOrigin(node.id) = null
      } else {
        val iterator = incomingNeighbors.iterator()
        var maxPath = -1
        var maxPathOrigin: Component[T] = null
        while (iterator.hasNext) {
          val neighbor = iterator.next()
          val neighborLongestPath = longestPath(neighbor.id)
          if (maxPath < neighborLongestPath) {
            maxPath = neighborLongestPath
            maxPathOrigin = neighbor
          }
        }
        longestPath(node.id) = maxPath+1
        longestPathOrigin(node.id) = maxPathOrigin
      }
      i += 1
    }
    val lastElementId = findIndexOfMax(longestPath)
    var element = findComponent(topoOrder, lastElementId)
    // trace back the longest path
    val buf = new util.ArrayList[Component[T]]()
    while (element != null) {
      buf.add(element)
      element = longestPathOrigin(element.id)
    }
    Collections.reverse(buf)
    import scala.collection.JavaConverters._
    buf.asScala
  }

  private def findIndexOfMax(a: Array[Int]): Int = {
    var i = 0
    var max = Int.MinValue
    var maxIndex = -1
    while (i < a.length) {
      if (a(i) > max) {
        max = a(i)
        maxIndex = i
      }
      i += 1
    }
    maxIndex
  }
  private def findComponent[T](a: Seq[Component[T]], id: Int): Component[T] = {
    var i = 0
    while (i < a.size) {
      if (a(i).id == id)
        return a(i)
      i += 1
    }
    null
  }
}

/**
  * Tarjan's algorithm for finding Strongly Connected Components.
  *
  * It's a modified implementation of Tarjan's algorithm that in addition to returning Strongly Connected Components
  * in reverse topological order, it returns edges of the DAG formed between components.
  *
  * The modification of the original implementation introduces another stack that keeps track of components. Components
  * are tracked along with a backlink: an id of the node that points at one of the nodes of the component. The
  * components are being popped from the stack when a new component is being built. We collect all components that
  * have backlinks with ids from the current component.
  *
  * The implementation has additional complexity of maintaining mappings between nodes and their components, components
  * and their ids, etc.
  *
  * @param nodes All nodes of the original graph
  * @param edges All edges of the original graph
  * @tparam T Type of nodes in the original graph
  */
private class TarjanSCC[T >: Null](nodes: Iterable[T], edges: T => Iterable[T]) {
  import TarjanSCC.{Component, SCCResult}
  type Component = TarjanSCC.Component[T]
  private val vertexIndices = new Indices
  private val componentIndices = new Indices
  private case class VertexData(vertex: T, index: Int, var lowLink: Int)
  // stack with fast `contains` operation
  private object vertexStack {
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
  private case class ComponentBacklink(component: Component, backLink: Int)
  private val componentStack: util.Stack[ComponentBacklink] = new util.Stack[ComponentBacklink]
  private val vertexData: mutable.Map[T, VertexData] = mutable.Map.empty
  private val collectedComponents: mutable.Buffer[Component] = mutable.Buffer.empty
  private val vertexToComponent: mutable.Map[Int, Component] = mutable.Map.empty
  private val componentEdges: ImmutableMultimap.Builder[Component, Component] = ImmutableMultimap.builder()

  def run(): SCCResult[T] = {
    for (v <- nodes)
      if (!visited(v))
        strongConnect(v)
    assert(componentStack.empty(), componentStack)
    val edges = componentEdges.build()
    SCCResult[T](collectedComponents, edges)
  }

  private def visited(v: T): Boolean = vertexData contains v
  private def strongConnect(v: T): Component = {
    val vIndex = vertexIndices.nextIndex()
    val vData = VertexData(v, index = vIndex, lowLink = vIndex)
    vertexData(v) = vData
    vertexStack.push(vData)
    val vEdges = edges(v)
    for (w <- vEdges) {
      if (!visited(w)) {
        val wComponent = strongConnect(w)
        if (wComponent != null) {
          componentStack.push(ComponentBacklink(wComponent, vIndex))
        }
        val wData = vertexData(w)
        vData.lowLink = math.min(vData.lowLink, wData.lowLink)
      } else if (vertexStack contains w) {
        vData.lowLink = math.min(vData.lowLink, vertexData(w).index)
      } else {
        // vertex has been visited and is not on the stack: it's part of an existing component
        // we should capture that dependency
        val wIndex = vertexData(w).index
        val wComponent = vertexToComponent(wIndex)
        componentStack.push(ComponentBacklink(wComponent, vData.index))
      }
    }

    if (vData.lowLink == vData.index) {
      val buf = mutable.Buffer.empty[T]
      val dataToUpdate = mutable.Buffer.empty[VertexData]
      var w: T = null
      do {
        w = vertexStack.pop().vertex
        dataToUpdate += vertexData(w)
        buf += w
      } while (w != v)
      val vertexSet = buf.toSet
      val component = Component(componentIndices.nextIndex())(vertexSet)
      collectedComponents += component
      var i = 0
      while (i < dataToUpdate.length) {
        val vertexData = dataToUpdate(i)
        vertexToComponent(vertexData.index) = component
        i += 1
      }
      while (!componentStack.empty && componentStack.peek.backLink >= vData.index) {
        val targetComponent = componentStack.pop().component
        componentEdges.put(component, targetComponent)
      }
      component
    } else null
  }
}

private class Indices {
  private var curIndex: Int = 0
  def nextIndex(): Int = {
    val r = curIndex
    curIndex += 1
    r
  }
}
