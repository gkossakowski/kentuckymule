package dotty.tools
package dotc
package ast

import core._
import util.Positions._, Types._, Contexts._, Constants._, Names._, Flags._
import StdNames._, Annotations._, Trees._
import Decorators._
//import config.Printers._
import collection.mutable

import scala.annotation.tailrec

/** Some creators for typed trees */
object tpd extends Trees.Instance[Type] {
  override implicit def modsDeco(mdef: MemberDef)(implicit ctx: Context): ModsDeco = ???

  override val cpy: TreeCopier = null

  /** A tree representing the same reference as the given type */
//  def ref(tp: NamedType)(implicit ctx: Context): Tree =
//  if (tp.isType) TypeTree(tp)
//  else if (prefixIsElidable(tp)) Ident(tp)
//  else if (tp.symbol.is(Module) && ctx.owner.isContainedIn(tp.symbol.moduleClass))
//    followOuterLinks(This(tp.symbol.moduleClass.asClass))
//  else if (tp.symbol hasAnnotation defn.ScalaStaticAnnot)
//    Ident(tp)
//  else tp.prefix match {
//    case pre: SingletonType => followOuterLinks(singleton(pre)).select(tp)
//    case pre => SelectFromTypeTree(TypeTree(pre), tp)
//  } // no checks necessary
}
