package dotty.tools
package dotc

import dotty.tools.dotc.core.Contexts.ContextBase
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Enter
import dotty.tools.dotc.util.{NoSource, SourceFile}
import dotc.core.Symbols._
import dotty.tools.dotc.core.Enter.{CompletionResult, TemplateMemberListCompleter}

import scala.reflect.io.PlainFile

object Main {

  protected def initCtx = (new ContextBase).initialCtx

  def getSource(fileName: String)(ctx: Context): SourceFile = {
    val f = new PlainFile(fileName)
    if (f.exists) new SourceFile(f)
    else {
      ctx.error(s"not found: $fileName")
      NoSource
    }
  }

  def countSymbols(ctx: Context): Int = {
    def countAllChildren(s: Symbol): Int = 1 + s.childrenIterator.map(countAllChildren).sum
    countAllChildren(ctx.definitions.rootPackage)
  }

  def main(args: Array[String]): Unit = {
    val start = System.currentTimeMillis()

    val ctx = initCtx.fresh
    try {
      for (i <- 1 to 1) {
        ctx.definitions.rootPackage.clear()
        val jobsNumber = processScalap(ctx)
        println("Number of jobs processed: " + jobsNumber)
      }
    } finally {
      println(s"It took ${System.currentTimeMillis() - start}")
    }
    val totalSize = countSymbols(ctx)
  }

  private val scalapFiles = Seq(
    "sample-projects/scalap/scala/tools/scalap/Arguments.scala",
    "sample-projects/scalap/scala/tools/scalap/ByteArrayReader.scala",
    "sample-projects/scalap/scala/tools/scalap/Classfile.scala",
    "sample-projects/scalap/scala/tools/scalap/Classfiles.scala",
    "sample-projects/scalap/scala/tools/scalap/CodeWriter.scala",
    "sample-projects/scalap/scala/tools/scalap/Decode.scala",
    "sample-projects/scalap/scala/tools/scalap/JavaWriter.scala",
    "sample-projects/scalap/scala/tools/scalap/Main.scala",
    "sample-projects/scalap/scala/tools/scalap/MetaParser.scala",
    "sample-projects/scalap/scala/tools/scalap/Properties.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/Memoisable.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/Result.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/Rule.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/Rules.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/ClassFileParser.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/Flags.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/ScalaSig.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/ScalaSigPrinter.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/SourceFileAttributeParser.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/Symbol.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/scalasig/Type.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/rules/SeqRule.scala",
    "sample-projects/scalap/scala/tools/scalap/scalax/Util/StringUtil.scala")
  def processScalap(implicit context: Context): Int = {
    val compilationUnits = for (filePath <- scalapFiles) yield {
      val source = getSource(filePath)(context)
      val unit = new CompilationUnit(source)
      val parser = new parsing.Parsers.Parser(source)(context)
      unit.untpdTree = parser.parse()
      unit
    }
    enterStabSymbolsForScalap(context)
    val enter = new Enter
    for (compilationUnit <- compilationUnits)
      enter.enterCompilationUnit(compilationUnit)(context)

    val jobsNumber = enter.processJobQueue(memberListOnly = false)(context)
    jobsNumber
  }

  import dotty.tools.dotc.core.Decorators._
  def enterStabSymbolsForScalap(implicit ctx: Context): Unit = {
    // enter java.io
    val root = ctx.definitions.rootPackage
    val javaPkg = new PackageSymbol("java".toTermName)
    root.addChild(javaPkg)
    val ioPkg = new PackageSymbol("io".toTermName)
    javaPkg.addChild(ioPkg)
    enterStubClasses(ioPkg, "ByteArrayOutputStream", "OutputStreamWriter", "PrintStream", "IOException", "Writer")

    // enter java.util
    val javaUtilPkg = new PackageSymbol("util".toTermName)
    javaPkg.addChild(javaUtilPkg)

    // enter scala
    val scalaPkg = new PackageSymbol("scala".toTermName)
    root.addChild(scalaPkg)

    // enter things visible by default from Predef, scala or java.lang packages into root package
    // normally, you would have separate package for scala and java.lang that are seen from every compilation unit
    // but we don't care about details when just trying to get some performance numbers
    enterStubClasses(root, "Nothing", "RuntimeException", "Function1", "String", "Array", "Char", "Unit", "Boolean",
      "Option", "List", "Byte", "Int", "Long", "Float", "Double", "Short", "AnyRef", "Any", "Function2")

    // enter scala.reflect
    val reflectPkg = new PackageSymbol("reflect".toTermName)
    scalaPkg.addChild(reflectPkg)
    enterStubClasses(reflectPkg, "NameTransformer")

    // enter scala.reflect.internal
    val reflectInternalPkg = new PackageSymbol("internal".toTermName)
    reflectPkg.addChild(reflectInternalPkg)

    // enter scala.reflect.internal.pickling
    val internalPicklingPkg = new PackageSymbol("pickling".toTermName)
    reflectInternalPkg.addChild(internalPicklingPkg)
    enterStubClasses(internalPicklingPkg, "ByteCodecs")

    // enter scala.reflect.internal.util
    val internalUtilPkg = new PackageSymbol("util".toTermName)
    reflectInternalPkg.addChild(internalUtilPkg)
    enterStubClasses(internalUtilPkg, "ScalaClassLoader")

    // enter scala.tools.nsc
    val toolsPkg = new PackageSymbol("tools".toTermName)
    scalaPkg.addChild(toolsPkg)
    val nscPkg = new PackageSymbol("nsc".toTermName)
    toolsPkg.addChild(nscPkg)
    enterStubClasses(nscPkg, "Settings")

    // enter scala.tools.nsc.classpath
    val classpathPkg = new PackageSymbol("classpath".toTermName)
    nscPkg.addChild(classpathPkg)
    enterStubClasses(classpathPkg, "AggregateClassPath", "ClassPathFactory")

    // enter scala.tools.nsc.util
    val nscUtilPkg = new PackageSymbol("util".toTermName)
    nscPkg.addChild(nscUtilPkg)
    enterStubClasses(nscUtilPkg, "ClassPath")

    // enter scala.tools.util
    val toolsUtilPkg = new PackageSymbol("util".toTermName)
    toolsPkg.addChild(toolsUtilPkg)
    enterStubClasses(toolsUtilPkg, "PathResolver")

    // enter scala.util
    val scalaUtilPkg = new PackageSymbol("util".toTermName)
    scalaPkg.addChild(scalaUtilPkg)
    enterStubClasses(scalaUtilPkg, "PropertiesTrait")

    // enter scala.collection.mutable
    val collectionPkg = new PackageSymbol("collection".toTermName)
    scalaPkg.addChild(collectionPkg)
    val mutablePkg = new PackageSymbol("mutable".toTermName)
    collectionPkg.addChild(mutablePkg)
    enterStubClasses(mutablePkg, "ListBuffer", "Set", "Map")

    // enter scala.language
    val languagePkg = new PackageSymbol("language".toTermName)
    scalaPkg.addChild(languagePkg)
    // we model implicitConversions as a class inside of a language package. In Scala, the "language" is an object
    // and "implicitConversions" is a val
    enterStubClasses(languagePkg, "implicitConversions", "postfixOps")
  }

  private class StubClassCompleter(sym: ClassSymbol) extends Enter.Completer(sym) {
    override def complete()(implicit context: Context): CompletionResult = {
      import core.Types._
      Enter.CompletedType(new ClassInfoType(sym))
    }
    override def isCompleted: Boolean = false
  }

  private def enterStubClasses(pkg: PackageSymbol, classNames: String*)(implicit context: Context): Unit = {
    for (className <- classNames) {
      val cls = new ClassSymbol(className.toTypeName)
      cls.completer = new StubClassCompleter(cls)
      pkg.addChild(cls)
    }
  }
}
