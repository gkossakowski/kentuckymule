package dotty.tools.dotc.core

import Names.Name

import scala.collection.mutable.{ListBuffer, Map}
import Decorators._

class Symbols { this: Contexts.Context =>
  import Symbols._
}

object Symbols {
  abstract class Symbol(val name: Name) {
    // the map is here to perform quick lookups by name
    // a single name can be overloaded hence we a collection as a value in the map
    private val childrenMap: Map[Name, ListBuffer[Symbol]] = Map.empty.withDefault(_ => ListBuffer.empty)
    private val childrenSeq: ListBuffer[Symbol] = ListBuffer.empty
    def addChild(sym: Symbol): Unit = {
      childrenMap(sym.name) = childrenMap(sym.name) += sym
      childrenSeq += sym
    }
    def childrenIterator: Iterator[Symbol] = childrenSeq.iterator
    def clear(): Unit = {
      childrenMap.clear()
      childrenSeq.clear()
    }
  }
  abstract class TermSymbol(name: Name) extends Symbol(name)
  abstract class TypeSymbol(name: Name) extends Symbol(name)

  final class PackageSymbol(name: Name) extends TermSymbol(name)
  final class ClassSymbol(name: Name) extends TypeSymbol(name)
  final class ModuleSymbol(name: Name) extends TermSymbol(name)
  final class ValDefSymbol(name: Name) extends TermSymbol(name)
  final class TypeDefSymbol(name: Name) extends TypeSymbol(name)
  final class DefDefSymbol(name: Name) extends TermSymbol(name)

  object NoSymbol extends Symbol("<none>".toTermName)
}
