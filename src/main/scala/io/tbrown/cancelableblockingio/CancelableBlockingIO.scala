package io.tbrown.cancelableblockingio

import cats.implicits._
import cats.effect.implicits._
import cats.effect.concurrent.Ref
import cats.effect._

import io.chrisdavenport.linebacker.DualContext

/*
  This code was copied and modified from Scalaz-ZIO: https://github.com/scalaz/scalaz-zio
  It was modified from this method: https://github.com/scalaz/scalaz-zio/blob/97ef17944b0126e25fa3b6b8926955b369570831/core/jvm/src/main/scala/scalaz/zio/blocking/Blocking.scala#L65
  Credit goes to that project and the maintainers and contributors.
*/

object CancelableF {
  final def blocking[F[_], A](effect: => A)(implicit F: ConcurrentEffect[F], DC: DualContext[F]): F[A] =
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
        fiber =
        F.cancelable[A] { cb =>
          DC.block {
            F.delay {
              withLock(thread.set(Some(Thread.currentThread())))

              try cb(Right[Throwable, A](effect))
              catch {
                case e: InterruptedException =>
                  Thread.interrupted()
                  cb(Left[Throwable, A](e))
              } finally withLock(thread.set(None))
            }
          }.guarantee(ref.get.flatten).start.void.toIO.unsafeRunSync()

          ref.get.flatten
        }
        _ <- ref.set(F.delay(withLock(thread.get.foreach(_.interrupt()))))
      } yield fiber).flatten
    }
  }
