package dotty.tools.dotc.core

import java.io.{File, PrintWriter}

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.{NoSource, SourceFile}

import scala.reflect.io.PlainFile

object IOUtils {

  def getSourceForContents(fileName: String)(ctx: Context): SourceFile = {
    val f = new PlainFile(fileName)
    if (f.exists) new SourceFile(f)
    else {
      ctx.error(s"not found: $fileName")
      NoSource
    }
  }

  def withTemporarySourceFile[T](contents: String, ctx: Context)(action: SourceFile => T): T =
    withTemporaryFile("enterTest", ".scala") { plainFile =>
      val printWriter = new PrintWriter(plainFile)
      printWriter.println(contents)
      printWriter.close()
      val sourceFile = getSourceForContents(plainFile.getAbsolutePath)(ctx)
      action(sourceFile)
    }

  /**
    * Creates a file in the default temporary directory, calls `action` with the file, deletes the file, and returns the result of calling `action`.
    * The name of the file will begin with `prefix`, which must be at least three characters long, and end with `postfix`, which has no minimum length.
    */
  def withTemporaryFile[T](prefix: String, postfix: String)(action: File => T): T =
  {
    val file = File.createTempFile(prefix, postfix)
    try { action(file) }
    finally { file.delete(); () }
  }

}
