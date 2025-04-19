ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.5"

val zioVersion = "3.2.0"

lazy val root = (project in file("."))
  .settings(
    name := "QuoTE",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-http" % zioVersion,

      "org.gnieh" % "tekstlib_2.12" % "0.1.1"  // rope library

    )
  )



