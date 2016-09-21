package dotty.tools.dotc
package printing

import core._
import Texts._, Types._, Flags._, Names._, NameOps._, Constants._
import Contexts.Context
import StdNames.{nme, tpnme}
import ast.Trees._, ast._
import java.lang.Integer.toOctalString
import scala.annotation.switch

class PlainPrinter(_ctx: Context) extends Printer {
  protected[this] implicit def ctx: Context = _ctx.addMode(Mode.Printing)

//  private var openRecs: List[RecType] = Nil

  protected def maxToTextRecursions = 100

  protected final def controlled(op: => Text): Text =
    if (ctx.toTextRecursions < maxToTextRecursions && ctx.toTextRecursions < maxSummarized)
      try {
        ctx.toTextRecursions += 1
        op
      } finally {
        ctx.toTextRecursions -= 1
      }
    else {
      if (ctx.toTextRecursions >= maxToTextRecursions)
        recursionLimitExceeded()
      "..."
    }

  protected def recursionLimitExceeded() = {
    ctx.warning("Exceeded recursion depth attempting to print.")
    if (ctx.debug) Thread.dumpStack()
  }

  /** If true, tweak output so it is the same before and after pickling */
  protected def homogenizedView: Boolean = ctx.settings.YtestPickler.value

  private def selfRecName(n: Int) = s"z$n"

  /** Render elements alternating with `sep` string */
  protected def toText(elems: Traversable[Showable], sep: String) =
    Text(elems map (_ toText this), sep)

  /** Render element within highest precedence */
  protected def toTextLocal(elem: Showable): Text =
    atPrec(DotPrec) { elem.toText(this) }

  /** Render element within lowest precedence */
  protected def toTextGlobal(elem: Showable): Text =
    atPrec(GlobalPrec) { elem.toText(this) }

  protected def toTextLocal(elems: Traversable[Showable], sep: String) =
    atPrec(DotPrec) { toText(elems, sep) }

  protected def toTextGlobal(elems: Traversable[Showable], sep: String) =
    atPrec(GlobalPrec) { toText(elems, sep) }

  def nameString(name: Name): String = name.toString + {
    if (ctx.settings.debugNames.value)
      if (name.isTypeName) "/T" else "/V"
    else ""
  }

  def toText(name: Name): Text = Str(nameString(name))

  protected def polyParamNameString(name: TypeName): String = name.toString

  protected def objectPrefix = "object "
  protected def packagePrefix = "package "

  protected def trimPrefix(text: Text) =
    text.stripPrefix(objectPrefix).stripPrefix(packagePrefix)

  /** String representation of a definition's type following its name,
   *  if symbol is completed, "?" otherwise.
   */
  protected def toTextRHS(optType: Option[Type]): Text = optType match {
    case Some(tp) => ??? ///toTextRHS(tp)
    case None => "?"
  }

  protected def toTextParents(parents: List[Type]): Text = ??? //Text(parents.map(toTextLocal), " with ")

  protected def treatAsTypeParam(sym: Symbol): Boolean = false
  protected def treatAsTypeArg(sym: Symbol): Boolean = false

  protected def varianceString(v: Int): String = v match {
    case -1 => "-"
    case 1 => "+"
    case _ => ""
  }

  @switch private def escapedChar(ch: Char): String = ch match {
    case '\b' => "\\b"
    case '\t' => "\\t"
    case '\n' => "\\n"
    case '\f' => "\\f"
    case '\r' => "\\r"
    case '"' => "\\\""
    case '\'' => "\\\'"
    case '\\' => "\\\\"
    case _ => if (ch.isControl) "\\0" + toOctalString(ch) else String.valueOf(ch)
  }

  def toText(const: Constant): Text = const.tag match {
    case StringTag => "\"" + escapedString(const.value.toString) + "\""
//    case ClazzTag => "classOf[" ~ toText(const.typeValue.classSymbol) ~ "]"
    case CharTag => s"'${escapedChar(const.charValue)}'"
    case LongTag => const.longValue.toString + "L"
    case EnumTag => const.symbolValue.name.toString
    case _ => String.valueOf(const.value)
  }

  protected def escapedString(str: String): String = str flatMap escapedChar

  def dclsText(syms: List[Symbol], sep: String): Text = Text(syms map dclText, sep)

  def toText[T >: Untyped](tree: Tree[T]): Text = {
    tree match {
      case node: Positioned =>
        def toTextElem(elem: Any): Text = elem match {
          case elem: Showable => elem.toText(this)
          case elem: List[_] => "List(" ~ Text(elem map toTextElem, ",") ~ ")"
          case elem => elem.toString
        }
        val nodeName = node.productPrefix
        val elems =
          Text(node.productIterator.map(toTextElem).toList, ", ")
        val tpSuffix =
          if (ctx.settings.printtypes.value && tree.hasType)
            " | " ~ toText(??? : Type /*tree.typeOpt*/)
          else
            Text()

        nodeName ~ "(" ~ elems ~ tpSuffix ~ ")" ~ node.pos.toString
      case _ =>
        tree.fallbackToText(this)
    }
  }.close // todo: override in refined printer

  private var maxSummarized = Int.MaxValue

  def summarized[T](depth: Int)(op: => T): T = {
    val saved = maxSummarized
    maxSummarized = ctx.toTextRecursions + depth
    try op
    finally maxSummarized = depth
  }

  def summarized[T](op: => T): T = ??? ///summarized(summarizeDepth)(op)

  def plain = this

  /** The name of the given symbol.
    * If !settings.debug, the original name where
    * expansions of operators are translated back to operator symbol.
    *  E.g. $eq => =.
    * If settings.uniqid, adds id.
    */
  override def nameString(sym: Symbol): String = ???

  /** The fully qualified name of the symbol */
  override def fullNameString(sym: Symbol): String = ???

  /** The kind of the symbol */
  override def kindString(sym: Symbol): String = ???

  /** Textual representation, including symbol's kind e.g., "class Foo", "method Bar".
    * If hasMeaninglessName is true, uses the owner's name to disambiguate identity.
    */
  override def toText(sym: Symbol): Text = ???

  /** Textual representation of symbol's declaration */
  override def dclText(sym: Symbol): Text = ???

  /** If symbol's owner is a printable class C, the text "in C", otherwise "" */
  override def locationText(sym: Symbol): Text = ???

  /** Textual representation of symbol and its location */
  override def locatedText(sym: Symbol): Text = ???

  /** A description of sym's location */
  override def extendedLocationText(sym: Symbol): Text = ???

  /** Textual representation of type */
  override def toText(tp: Type): Text = ???
}

