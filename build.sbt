ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.14"

lazy val root = (project in file("."))
  .settings(
    name := "MusicRecommendation"
  )

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "3.3.0"
)