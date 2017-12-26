package kentuckymule.core

import java.util

object CollectionUtils {

  final def asScalaList[T](javaList: util.ArrayList[T]): List[T] = {
    var i = javaList.size() - 1
    var res: List[T] = Nil
    while (i >= 0) {
      res = javaList.get(i) :: res
      i -= 1
    }
    res
  }

  final def asScalaList2[T](javaList: util.ArrayList[util.ArrayList[T]]): List[List[T]] = {
    var i = javaList.size() - 1
    var res: List[List[T]] = Nil
    while (i >= 0) {
      val innerList = asScalaList(javaList.get(i))
      res = innerList :: res
      i -= 1
    }
    res
  }

  @inline final def foreachWithIndex[T](xs: List[T])(f: (T, Int) => Unit): Unit = {
    var index = 0
    var remaining = xs
    while (remaining.nonEmpty) {
      f(remaining.head, index)
      remaining = remaining.tail
      index += 1
    }
  }

  @inline final def mapToArrayList[T, U](xs: List[T])(f: T => U): util.ArrayList[U] = {
    val result = new util.ArrayList[U]()
    var remaining = xs
    while (remaining.nonEmpty) {
      val elem = f(remaining.head)
      result.add(elem)
      remaining = remaining.tail
    }
    result
  }

}
