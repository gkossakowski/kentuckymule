package dotty.tools.dotc
package core

import Types._, util.Positions._, Contexts._, Constants._
import config.ScalaVersion
import StdNames._
import dotty.tools.dotc.ast.untpd

object Annotations {

  abstract class Annotation {
    def appliesToModule: Boolean = true // for now; see remark in SymDenotations
  }
}
