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

val root = (project in file(".")).aggregate(kentuckymule)
