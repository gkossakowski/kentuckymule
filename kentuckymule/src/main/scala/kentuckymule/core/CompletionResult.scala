package kentuckymule.core

import kentuckymule.core.Symbols.{NoSymbol, Symbol}
import kentuckymule.core.Types.Type

sealed trait CompletionResult
case class CompletedType(tpe: Type) extends CompletionResult

case class IncompleteDependency(sym: Symbol) extends LookupAnswer with CompletionResult

sealed trait LookupAnswer
case class LookedupSymbol(sym: Symbol) extends LookupAnswer
case object NotFound extends LookupAnswer with CompletionResult
object LookupAnswer {
  final def symToLookupAnswer(sym: Symbol): LookupAnswer = {
    if (sym == NoSymbol)
      NotFound
    else
      LookedupSymbol(sym)
  }
}
