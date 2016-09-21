val common: Seq[Setting[_]] = Seq(
  organization := "gkk",
  scalaVersion := "2.11.8"
)

val kentuckymule = project.
  settings(common:_*).
  settings(
    // https://mvnrepository.com/artifact/org.scala-lang/scala-reflect
    libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.11.8"
  )

lazy val bench = project.dependsOn(kentuckymule).enablePlugins(JmhPlugin).settings(common:_*)

lazy val root = (project in file(".")).aggregate(kentuckymule, bench)
