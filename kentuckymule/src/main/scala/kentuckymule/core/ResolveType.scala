package kentuckymule.core

import java.util

import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.ast.untpd.{EmptyTree, Function, InfixOp, Parens, PostfixOp, Tree, Tuple}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.StdNames.{nme, tpnme}
import kentuckymule.core.Enter.{LookupScope, functionNamesByArity}
import kentuckymule.core.Symbols.{ClassSymbol, ModuleSymbol, Symbol, ValDefSymbol, NoSymbol}
import kentuckymule.core.Types.{AppliedType, SymRef, TupleType, Type, WildcardType}

object ResolveType {

  def resolveSelectors(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer =
    t match {
      case Ident(identName) => parentLookupScope.lookup(identName)
      case Select(qual, selName) =>
        val ans = resolveSelectors(qual, parentLookupScope)
        ans match {
          case LookedupSymbol(qualSym) =>
            if (qualSym.isComplete) {
              val selSym = qualSym.info.lookup(selName)
              if (selSym != NoSymbol)
                LookedupSymbol(selSym)
              else
                NotFound
            } else IncompleteDependency(qualSym)
          case _ => ans
        }
      // TODO: right now we interpret C.super[M] as just C.super (M is ignored)
      case Super(qual, _) => resolveSelectors(qual, parentLookupScope)
      case This(tpnme.EMPTY) => parentLookupScope.enclosingClass
      case This(thisQual) => parentLookupScope.lookup(thisQual)
      case _ => sys.error(s"Unhandled tree $t at ${t.pos}")
    }

  def resolveTypeTree(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): CompletionResult = t match {
    case AppliedTypeTree(tpt, args) =>
      val resolvedTpt = resolveTypeTree(tpt, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case uncompleted => return uncompleted
      }
      var remainingArgs = args
      val resolvedArgs = new util.ArrayList[Type]()
      while (remainingArgs.nonEmpty) {
        val resolvedArg = resolveTypeTree(remainingArgs.head, parentLookupScope)
        resolvedArg match {
          case CompletedType(argTpe) => resolvedArgs.add (argTpe)
          case _ => return resolvedArg
        }
        remainingArgs = remainingArgs.tail
      }
      CompletedType(AppliedType(resolvedTpt, resolvedArgs.toArray(new Array[Type](resolvedArgs.size))))
    // ParentClass(foo) is encoded as a constructor call with a tree of shape
    // Apply(Select(New(Ident(ParentClass)),<init>),List(Ident(foo)))
    // we want to extract the Ident(ParentClass)
    case Apply(Select(New(tp), nme.CONSTRUCTOR), _) =>
      resolveTypeTree(tp, parentLookupScope)
    // strip down any constructor applications, e.g.
    // class Foo extends Bar[T]()(t)
    case Apply(qual, _) =>
      resolveTypeTree(qual, parentLookupScope)
    case Parens(t2) => resolveTypeTree(t2, parentLookupScope)
    case Function(args, res) =>
      val resolvedFunTypeArgs = new util.ArrayList[Type]()
      var remainingArgs = args
      while (remainingArgs.nonEmpty) {
        val resolvedArg = resolveTypeTree(remainingArgs.head, parentLookupScope)
        resolvedArg match {
          case CompletedType(argTpe) => resolvedFunTypeArgs.add(argTpe)
          case _ => return resolvedArg
        }
        remainingArgs = remainingArgs.tail
      }
      val resolvedRes = resolveTypeTree(res, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val funName = functionNamesByArity(resolvedFunTypeArgs.size)
      resolvedFunTypeArgs.add(resolvedRes)
      val functionSym = parentLookupScope.lookup(funName) match {
        case LookedupSymbol(sym) => sym
        case NotFound => sys.error(s"Can't resolve $funName")
        case x: IncompleteDependency => return x
      }
      CompletedType(AppliedType(SymRef(functionSym), resolvedFunTypeArgs.toArray[Type](new Array(resolvedFunTypeArgs.size))))
    // TODO: I ignore a star indicator of a repeated parameter as it's not essential and fairly trivial to deal with
    case PostfixOp(ident, nme.raw.STAR) =>
      resolveTypeTree(ident, parentLookupScope)
    case Tuple(trees) =>
      var remainingTrees = trees
      val resolvedTrees = new util.ArrayList[Type]()
      while (remainingTrees.nonEmpty) {
        val resolvedTree = resolveTypeTree(remainingTrees.head, parentLookupScope)
        resolvedTree match {
          case CompletedType(treeTpe) => resolvedTrees.add(treeTpe)
          case _ => return resolvedTree
        }
        remainingTrees = remainingTrees.tail
      }
      CompletedType(TupleType(resolvedTrees.toArray(new Array[Type](resolvedTrees.size))))
    // TODO: we ignore by name argument `=> T` and resolve it as `T`
    case ByNameTypeTree(res) =>
      resolveTypeTree(res, parentLookupScope)
    // TODO: I ignore AndTypeTree and pick just the left side, for example the `T with U` is resolved to `T`
    case t@AndTypeTree(left, right) =>
      if (context.verbose)
        println(s"Ignoring $t (printed because this hacky shortcut is non-trivial)")
      resolveTypeTree(left, parentLookupScope)
    case TypeBoundsTree(EmptyTree, EmptyTree) =>
      CompletedType(WildcardType)
    case InfixOp(left, op, right) =>
      val resolvedLeftType = resolveTypeTree(left, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedRightType = resolveTypeTree(left, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedOp = parentLookupScope.lookup(op) match {
        case LookedupSymbol(sym) => SymRef(sym)
        case NotFound =>
          sys.error(s"Can't resolve $op")
        case incomplete: IncompleteDependency => return incomplete
      }
      CompletedType(AppliedType(resolvedOp, Array[Type](resolvedLeftType, resolvedRightType)))
    case SelectFromTypeTree(qualifier, name) =>
      val resolvedQualifier = resolveTypeTree(qualifier, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedSelect = if (resolvedQualifier.typeSymbol.isComplete)
        resolvedQualifier.typeSymbol.info.lookup(name)
      else
        return IncompleteDependency(resolvedQualifier.typeSymbol)
      if (resolvedSelect != NoSymbol)
        CompletedType(SymRef(resolvedSelect))
      else
        NotFound
    case SingletonTypeTree(ref) =>
      def symRefAsCompletionResult(sym: Symbol): CompletionResult =
        if (sym.isComplete) CompletedType(SymRef(sym)) else IncompleteDependency(sym)
      val result = resolveTypeTree(ref, parentLookupScope)
      result match {
        case CompletedType(resolvedPath) => resolvedPath match {
          case SymRef(cls: ClassSymbol) => symRefAsCompletionResult(cls)
          case SymRef(mod: ModuleSymbol) => symRefAsCompletionResult(mod.clsSym)
          case SymRef(valDef: ValDefSymbol) =>
            if (valDef.isComplete) CompletedType(valDef.info.resultType) else IncompleteDependency(valDef)
        }
        case other => other
      }
    case Annotated(_, arg) => resolveTypeTree(arg, parentLookupScope)
    // TODO: refinements are simply dropped at the moment
    case RefinedTypeTree(tpt, _) => resolveTypeTree(tpt, parentLookupScope)
    case This(tpnme.EMPTY) =>
      val resolvedCls = parentLookupScope.enclosingClass
      resolvedCls match {
        case LookedupSymbol(sym) => CompletedType(SymRef(sym))
        case NotFound =>
          sys.error(s"Can't resolve This at ${t.pos}")
        case incomplete: IncompleteDependency => incomplete
      }
    // idnet or select?
    case other =>
      val resolvedSel = resolveSelectors(other, parentLookupScope)
      resolvedSel match {
        case LookedupSymbol(sym) => CompletedType(SymRef(sym))
        case NotFound =>
          sys.error(s"Can't resolve selector $other")
        case incomplete: IncompleteDependency => incomplete
      }
  }

}
