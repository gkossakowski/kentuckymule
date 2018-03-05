package dotty.tools
package dotc
package core

import kentuckymule.core.Types._
import Contexts._
import dotty.tools.dotc.core.Names.Name
import kentuckymule.core.LookupAnswer
import kentuckymule.core.Symbols._

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

  object ByteType extends Type {
    override def typeSymbol: Symbol = ???
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = ???
  }
  object ShortType extends Type  {
    override def typeSymbol: Symbol = ???
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = ???
  }
  object CharType extends Type  {
    override def typeSymbol: Symbol = ???
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = ???
  }
  object IntType extends Type  {
    override def typeSymbol: Symbol = ???
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = ???
  }
  object LongType extends Type  {
    override def typeSymbol: Symbol = ???
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = ???
  }
  object DoubleType extends Type  {
    override def typeSymbol: Symbol = ???
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = ???
  }
  object FloatType extends Type  {
    override def typeSymbol: Symbol = ???
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = ???
  }
  object BooleanType extends Type  {
    override def typeSymbol: Symbol = ???
    override def lookup(name: Name)(implicit contexts: Context): LookupAnswer = ???
  }

  val rootPackage: PackageSymbol = {
    val rootPkg = PackageSymbol(StdNames.nme.ROOTPKG)
    rootPkg.info = new RootPackageInfoType(rootPkg)
    rootPkg
  }
  val emptyPackage: PackageSymbol = {
    val emptyPkgSym = PackageSymbol(StdNames.nme.EMPTY_PACKAGE)
    emptyPkgSym.info = new EmptyPackageInfoType(emptyPkgSym)
    rootPackage.addChild(emptyPkgSym)
    emptyPkgSym
  }

  def init()(implicit ctx: Context) = {
    this.ctx = ctx
  }
}
