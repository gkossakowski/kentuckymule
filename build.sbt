val common: Seq[Setting[_]] = Seq(
  organization := "gkk",
  scalaVersion := "2.12.4",
  scalacOptions ++= List("-opt:l:inline",  "-opt-inline-from:**", "-opt-warnings", "-deprecation", "-feature"),
  // uncomment for detailed inliner logs
  //scalacOptions ++= List("-Yopt-log-inline", "_", "-opt-warnings"),
)

val kentuckymule = project.
  settings(common:_*).
  settings(
    // https://mvnrepository.com/artifact/org.scala-lang/scala-reflect
    libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    // https://mvnrepository.com/artifact/com.google.guava/guava
    libraryDependencies += "com.google.guava" % "guava" % "19.0",
    // https://mvnrepository.com/artifact/jline/jline
    // included for progress bar support
    libraryDependencies += "jline" % "jline" % "2.14.2",
    libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.2" % "test",
    testFrameworks += new TestFramework("utest.runner.Framework")
  )

lazy val bench = project.dependsOn(kentuckymule).enablePlugins(JmhPlugin).settings(common:_*)

lazy val root = (project in file(".")).aggregate(kentuckymule, bench)
