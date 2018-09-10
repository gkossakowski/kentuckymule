package kentuckymule

object Timer {
  var startTime: Long = 0
  def init(): Unit = startTime = System.currentTimeMillis()

  def sinceInit(s: String): Unit =
    println(s"[${System.currentTimeMillis()-startTime} ms] $s")
}
