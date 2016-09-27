package dotty.tools
package dotc
package core

import Types._
import Contexts._
import dotty.tools.dotc.core.Symbols.{NoSymbol, PackageSymbol}
//import Flags._, Scopes._, Decorators._, NameOps._, util.Positions._, Periods._
//import unpickleScala2.Scala2Unpickler.ensureConstructor
//import scala.annotation.{ switch, meta }
//import scala.collection.{ mutable, immutable }
//import PartialFunction._
//import collection.mutable
//import scala.reflect.api.{ Universe => ApiUniverse }

object Definitions {
  val MaxTupleArity, MaxAbstractFunctionArity = 22
  val MaxFunctionArity = 30
    // Awaiting a definite solution that drops the limit altogether, 30 gives a safety
    // margin over the previous 22, so that treecopiers in miniphases are allowed to
    // temporarily create larger closures. This is needed in lambda lift where large closures
    // are first formed by treecopiers before they are split apart into parameters and
    // environment in the lambdalift transform itself.
}

/** A class defining symbols and types of standard definitions
 *
 *  Note: There's a much nicer design possible once we have implicit functions.
 *  The idea is explored to some degree in branch wip-definitions (#929): Instead of a type
 *  and a separate symbol definition, we produce in one line an implicit function from
 *  Context to Symbol, and possibly also the corresponding type. This cuts down on all
 *  the duplication encountered here.
 *
 *  wip-definitions tries to do the same with an implicit conversion from a SymbolPerRun
 *  type to a symbol type. The problem with that is universal equality. Comparisons will
 *  not trigger the conversion and will therefore likely return false results.
 *
 *  So the branch is put on hold, until we have implicit functions, which will always
 *  automatically be dereferenced.
 */
class Definitions {
  import Definitions._

  private implicit var ctx: Context = _

  object ByteType extends Type
  object ShortType extends Type
  object CharType extends Type
  object IntType extends Type
  object LongType extends Type
  object DoubleType extends Type
  object FloatType extends Type
  object BooleanType extends Type

  val rootPackage = new PackageSymbol(StdNames.nme.ROOTPKG)

  def init()(implicit ctx: Context) = {
    this.ctx = ctx
  }
}
