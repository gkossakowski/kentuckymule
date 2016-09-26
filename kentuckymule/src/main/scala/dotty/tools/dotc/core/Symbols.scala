package dotty.tools.dotc.core

import Names.Name

import scala.collection.mutable.{ListBuffer, Map}

import com.google.common.collect.{ArrayListMultimap, ListMultimap}

import scala.collection.JavaConverters._

import Decorators._

class Symbols { this: Contexts.Context =>
  import Symbols._
}

object Symbols {
  abstract class Symbol(val name: Name) {
    // the map is here to perform quick lookups by name
    // a single name can be overloaded hence we a collection as a value in the map
    private val childrenMap: ListMultimap[Name, Symbol] = ArrayListMultimap.create()
    private val childrenSeq: ListBuffer[Symbol] = ListBuffer.empty
    def addChild(sym: Symbol): Unit = {
      childrenMap.put(sym.name, sym)
      childrenSeq += sym
    }
    def childrenIterator: Iterator[Symbol] = childrenSeq.iterator
    def clear(): Unit = {
      childrenMap.clear()
      childrenSeq.clear()
    }
    def lookup(name: Name): Symbol = childrenMap.get(name).get(0)
    def lookupAll(name: Name): Seq[Symbol] = childrenMap.get(name).asScala
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
