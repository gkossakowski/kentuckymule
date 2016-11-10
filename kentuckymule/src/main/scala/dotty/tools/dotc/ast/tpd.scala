package dotty.tools
package dotc
package ast

import core._
import kentuckymule.core.Types._, Contexts._


/** Some creators for typed trees */
object tpd extends Trees.Instance[Type] {
  override implicit def modsDeco(mdef: MemberDef)(implicit ctx: Context): ModsDeco = ???

  override val cpy: TreeCopier = null

}
