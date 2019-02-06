package io.tbrown.cancelableblockingio

import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import io.chrisdavenport.linebacker.contexts.Executors

import scala.concurrent.ExecutionContext

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    def blah() = {
      Thread.sleep(5000)
      println("done")
    }

    Executors.unbound[IO].use { es =>
      val ec = ExecutionContext.fromExecutorService(es)

      for {
        //fiber <- blocking[IO, Unit](blah())(ec).start
        fiber <- blocking[IO, Unit](blah())(ec).start
        _ <- IO(Thread.sleep(1000))
//        _ <- fiber.join
        _ <- IO(println("Past"))
        _ <- fiber.cancel

//        _ <- IO(println("We have fully canceled now"))
//        _ <- fiber.join
      } yield ExitCode.Success
    }
  }

  final def blocking[F[_], A](effect: => A)(blockingEC: ExecutionContext)(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): F[A] =
    F.suspend {
      import java.util.concurrent.locks.ReentrantLock
      import java.util.concurrent.atomic.AtomicReference

      val lock   = new ReentrantLock()
      val thread = new AtomicReference[Option[Thread]](None)

      def withLock[B](b: => B): B =
        try {
          lock.lock(); b
        } finally lock.unlock()

      (for {
          ref <- Ref[F].of(F.unit)
          fiber = //ugly!
            F.cancelable[A] { cb =>
              F.toIO[Unit] {
                F.start {
                  CS.evalOn(blockingEC) {
                    F.guarantee {
                      F.delay {
                        withLock(thread.set(Some(Thread.currentThread())))

                        try cb(Right[Throwable, A](effect))
                        catch {
                          case e: InterruptedException =>
                            Thread.interrupted()
                            cb(Left[Throwable, A](e))
                        } finally withLock(thread.set(None))
                      }
                    }(ref.get.flatten)
                  }
                }.void
              }.unsafeRunSync()

              ref.get.flatten
            }
          _ <- ref.set(F.delay(withLock(thread.get.foreach(_.interrupt()))))
        } yield fiber).flatten
    }
}