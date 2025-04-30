ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.5"

val zioHttpVersion = "3.2.0"
val zioVersion = "2.1.17"
val circeVersion = "0.14.12"

lazy val quote_ot    = RootProject(uri("https://github.com/Kon-Chi/QuoTE-OT.git#5cb47641b1cad27cdb41e76b9d2cf2e81f09c571"))
lazy val piece_table = RootProject(uri("https://github.com/Kon-Chi/PieceTable.git#93285a8b6ae142c0f704a4a31faa1622cea91356"))

lazy val root = (project in file("."))
  .dependsOn(quote_ot)
  .dependsOn(piece_table)
  .settings(
    name := "QuoTE",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-http" % zioHttpVersion,
      "dev.zio" %% "zio-redis" % "1.1.3",

      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
    )
  )

