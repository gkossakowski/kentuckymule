package kentuckymule

import jline.{Terminal, TerminalFactory}

// Progress bar code shamelessly stolen from
// https://github.com/yarnpkg/yarn/blob/master/src/reporters/console/progress-bar.js
class ProgressBar(var total: Int) {
  private val chars: Array[Char] = Array('█', '░')
  var curr = 0
  ProgressBar.clearLine()

  private val terminal: Terminal = TerminalFactory.create()

  def render(): Unit = {
    var ratio = curr.toDouble / total.toDouble
    ratio = Math.min(Math.max(ratio, 0), 1)
    var bar = s" $curr/$total"

    val availableSpace = Math.max(0, terminal.getWidth - bar.length - 1)
    val width = Math.min(total, availableSpace)
    val completeLength = Math.round(width * ratio).toInt
    val complete = chars(0).toString * completeLength
    val incomplete = chars(1).toString * (width - completeLength)
    bar = s"$complete$incomplete$bar"
    ProgressBar.toStartOfLine()
    System.out.print(bar)
  }
}

object ProgressBar {

  private def toStartOfLine(): Unit = cursorTo(0)

  private def cursorTo(x: Int): Unit =
    System.out.print("\u001b[" + (x + 1) + "G")

  private def clearLine(): Unit =
    System.out.print("\u001b[2K")

}
