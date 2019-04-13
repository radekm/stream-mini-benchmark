package bench

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import monix.eval.Task
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Fork, Measurement, Mode, Scope, State, TearDown, Warmup}
import org.openjdk.jmh.infra.Blackhole
import scalaz.zio.DefaultRuntime

import scala.concurrent.Await
import scala.concurrent.duration._

@Warmup(iterations = 5)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(value = 3, jvmArgsAppend = Array("-Xmx1G"))
class MyBenchmark {
  val monixGlobalScheduler = monix.execution.Scheduler.global
  val zioRuntime = new DefaultRuntime {}
  val akkaSystem = ActorSystem()
  val akkaMaterializer = {
    // We don't want to make this implicit accessible everywhere.
    implicit val s = akkaSystem
    ActorMaterializer()
  }

  // Stop Akka.
  @TearDown
  def shutdown(): Unit = {
    akkaSystem.terminate()
    // Wait until termination completes.
    Await.ready(akkaSystem.whenTerminated, 15.seconds)
  }

  // ------------------------------------------------------
  // Append static stream - parens accumulated on the left

  val NumberOfAppends = 1000 * 1000

  @Benchmark
  def iterableAppendStaticLeftAssoc(bh: Blackhole): Unit = {
    val static = Iterable(1, 2, 3, 4, 5)

    var x = static
    for (i <- 1 to NumberOfAppends) {
      x = x ++ static
    }

    val result = x.foldLeft(0)(_ + _)
    bh.consume(result)
  }

  @Benchmark
  def monixObservableAppendStaticLeftAssoc(bh: Blackhole): Unit = {
    import monix.reactive.Observable

    val static = Observable(1, 2, 3, 4, 5)

    var x = static
    for (i <- 1 to NumberOfAppends) {
      x = x ++ static
    }

    implicit val s = monixGlobalScheduler
    val result = x.foldLeftL(0)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def monixIterantAppendStaticLeftAssoc(bh: Blackhole): Unit = {
    import monix.tail.Iterant

    val static = Iterant.fromList[Task, Int](List(1, 2, 3, 4, 5))

    var x = static
    for (i <- 1 to NumberOfAppends) {
      x = x ++ static
    }

    implicit val s = monixGlobalScheduler
    val result = x.foldLeftL(0)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def fs2AppendStaticLeftAssoc(bh: Blackhole): Unit = {
    import fs2.Stream

    val static = Stream[Task, Int](1, 2, 3, 4, 5)

    var x = static
    for (i <- 1 to NumberOfAppends) {
      x = x ++ static
    }

    implicit val s = monixGlobalScheduler
    val result = x.compile.fold(0)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def zioAppendStaticLeftAssoc(bh: Blackhole): Unit = {
    import scalaz.zio.stream.ZStream

    val static = ZStream[Int](1, 2, 3, 4, 5)

    var x = static
    for (i <- 1 to NumberOfAppends) {
      x = x ++ static
    }

    val result = zioRuntime.unsafeRun(x.foldLeft(0)(_ + _))
    bh.consume(result)
  }

  @Benchmark
  def akkaAppendStaticLeftAssoc(bh: Blackhole): Unit = {
    import akka.stream.scaladsl.{Sink, Source}

    val static = Source(List(1, 2, 3, 4, 5))

    var x = static
    for (i <- 1 to NumberOfAppends) {
      // NOTE: Akka doesn't have `append` so we have to use `prepend` and switch arguments.
      x = static prepend x
    }

    val result = Await.result(x.runWith(Sink.fold(0)(_ + _))(akkaMaterializer), Duration.Inf)
    bh.consume(result)
  }

  // ------------------------------------------------------
  // Filter stream

  val UnfilteredNumbers = Iterable.range[Long](0L, 5000L * 1000)

  @Benchmark
  def iterableFilter(bh: Blackhole): Unit = {
    val x = UnfilteredNumbers.filter(_ % 2 == 0)

    val result = x.foldLeft(0L)(_ + _)
    bh.consume(result)
  }

  @Benchmark
  def monixObservableFilter(bh: Blackhole): Unit = {
    import monix.reactive.Observable

    val x = Observable.fromIterable(UnfilteredNumbers).filter(_ % 2 == 0)

    implicit val s = monixGlobalScheduler
    val result = x.foldLeftL(0L)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def monixIterantFilter(bh: Blackhole): Unit = {
    import monix.tail.Iterant

    val x = Iterant.fromIterable[Task, Long](UnfilteredNumbers).filter(_ % 2 == 0)

    implicit val s = monixGlobalScheduler
    val result = x.foldLeftL(0L)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def fs2Filter(bh: Blackhole): Unit = {
    import fs2.Stream

    // NOTE: FS2 doesn't have `fromIterator`.
    val x = Stream.fromIterator[Task, Long](UnfilteredNumbers.iterator).filter(_ % 2 == 0)

    implicit val s = monixGlobalScheduler
    val result = x.compile.fold(0L)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def zioFilter(bh: Blackhole): Unit = {
    import scalaz.zio.stream.ZStream

    val x = ZStream.fromIterable(UnfilteredNumbers).filter(_ % 2 == 0)

    val result = zioRuntime.unsafeRun(x.foldLeft(0L)(_ + _))
    bh.consume(result)
  }

  @Benchmark
  def akkaFilter(bh: Blackhole): Unit = {
    import akka.stream.scaladsl.{Sink, Source}

    // NOTE: Akka's Source(UnfilteredNumbers) doesn't work because it needs `immutable.Iterable`,
    //       not ordinary `Iterable`.
    val x = Source.fromIterator(() => UnfilteredNumbers.iterator).filter(_ % 2 == 0)

    val result = Await.result(x.runWith(Sink.fold(0L)(_ + _))(akkaMaterializer), Duration.Inf)
    bh.consume(result)
  }

  // ------------------------------------------------------
  // flatMap - dynamic stream (stream library doesn't know it's elements ahead of time)

  val NumberOfFlatMaps = 1000 * 1000
  val FewNumbers = Iterable.range[Int](0, 5)

  @Benchmark
  def iterableFlatMap(bh: Blackhole): Unit = {
    val x = Iterable.range(0, NumberOfFlatMaps)
      .flatMap(_ => FewNumbers)

    val result = x.foldLeft(0)(_ + _)
    bh.consume(result)
  }

  @Benchmark
  def monixObservableFlatMap(bh: Blackhole): Unit = {
    import monix.reactive.Observable

    val x = Observable.range(0, NumberOfFlatMaps)
      .flatMap(_ => Observable.fromIterable(FewNumbers))

    implicit val s = monixGlobalScheduler
    val result = x.foldLeftL(0)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def monixIterantFlatMap(bh: Blackhole): Unit = {
    import monix.tail.Iterant

    val x = Iterant.range[Task](0, NumberOfFlatMaps)
      .flatMap(_ => Iterant.fromIterable(FewNumbers))

    implicit val s = monixGlobalScheduler
    val result = x.foldLeftL(0)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def fs2FlatMap(bh: Blackhole): Unit = {
    import fs2.Stream

    val x = Stream.range[Task](0, NumberOfFlatMaps)
      // NOTE: Cannot be compiled without type-annotating `flatMap` and `fromIterator`.
      .flatMap[Task, Int](_ => Stream.fromIterator[Task, Int](FewNumbers.iterator))

    implicit val s = monixGlobalScheduler
    val result = x.compile.fold(0)(_ + _).runSyncUnsafe()
    bh.consume(result)
  }

  @Benchmark
  def zioFlatMap(bh: Blackhole): Unit = {
    import scalaz.zio.stream.ZStream

    // NOTE: `ZStream.range` has inclusive upper bound not exclusive as is usual.
    val x = ZStream.range(1, NumberOfFlatMaps)
      .flatMap(_ => ZStream.fromIterable(FewNumbers))

    val result = zioRuntime.unsafeRun(x.foldLeft(0)(_ + _))
    bh.consume(result)
  }

  @Benchmark
  def akkaFlatMap(bh: Blackhole): Unit = {
    import akka.stream.scaladsl.{Sink, Source}

    // NOTE: Akka is missing function `Source.range`
    // NOTE: Akka is missing `flatMap`.
    val x = Source(0 until NumberOfFlatMaps)
      .flatMapConcat(_ => Source.fromIterator(() => FewNumbers.iterator))

    val result = Await.result(x.runWith(Sink.fold(0)(_ + _))(akkaMaterializer), Duration.Inf)
    bh.consume(result)
  }
}
