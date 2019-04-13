name := "stream-mini-benchmark"

scalaVersion := "2.12.8"

enablePlugins(JmhPlugin)

libraryDependencies += "io.monix" %% "monix" % "3.0.0-RC2"

libraryDependencies += "co.fs2" %% "fs2-core" % "1.0.4"

libraryDependencies += "org.scalaz" %% "scalaz-zio" % "1.0-RC3"

libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.22"
