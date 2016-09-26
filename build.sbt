val common: Seq[Setting[_]] = Seq(
  organization := "gkk",
  scalaVersion := "2.11.8"
)

val kentuckymule = project.
  settings(common:_*).
  settings(
    // https://mvnrepository.com/artifact/org.scala-lang/scala-reflect
    libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.11.8",
    // https://mvnrepository.com/artifact/com.google.guava/guava
    libraryDependencies += "com.google.guava" % "guava" % "19.0",
    libraryDependencies += "com.lihaoyi" %% "utest" % "0.4.3" % "test",
    testFrameworks += new TestFramework("utest.runner.Framework")
  )

lazy val bench = project.dependsOn(kentuckymule).enablePlugins(JmhPlugin).settings(common:_*)

lazy val root = (project in file(".")).aggregate(kentuckymule, bench)
