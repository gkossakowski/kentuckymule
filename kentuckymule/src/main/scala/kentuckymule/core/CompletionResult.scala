package kentuckymule.core

import kentuckymule.core.Symbols.Symbol
import kentuckymule.core.Types.Type

sealed trait CompletionResult
case class CompletedType(tpe: Type) extends CompletionResult

case class IncompleteDependency(sym: Symbol) extends LookupAnswer with CompletionResult

sealed trait LookupAnswer
case class LookedupSymbol(sym: Symbol) extends LookupAnswer
case object NotFound extends LookupAnswer with CompletionResult
