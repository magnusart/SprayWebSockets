import sbt._
import Keys._

object Build extends sbt.Build{

  lazy val proj = Project(
    "spray-websockets",
    file("."),
    settings =
      Defaults.defaultSettings ++ Seq(
      organization  := "com.github.lihaoyi",
      version       := "0.1",
      scalaVersion  := "2.10.3",

      resolvers ++= Seq(
        "typesafe repo"      at "http://repo.typesafe.com/typesafe/releases/",
        "spray"              at "http://repo.spray.io",
      ),
      libraryDependencies ++= Seq(
        "io.spray"            %   "spray-can"     % "1.2.0",
        "com.typesafe.akka"   %%  "akka-actor"    % "2.2.3",
        "com.typesafe.akka"   %%  "akka-testkit"  % "2.2.3" % "test",
        "org.scalatest"       % "scalatest_2.10"  % "2.0"   % "test"
      )
    )
  )
}