package io.tbrown.cancelableblockingio

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Ref

import io.chrisdavenport.linebacker.DualContext
import io.chrisdavenport.linebacker.contexts.Executors

import org.specs2._

import scala.concurrent.ExecutionContext

object MainSpec extends mutable.Specification {

  def testBlockingIOIsCancelable(implicit cs: ContextShift[IO], dc: DualContext[IO]) =
    for {
      done  <- Ref.of[IO, Boolean](false)
      start <- IO(OneShot.make[Unit])
      fiber <- CancelableF.blocking[IO, Unit] { start.set(()); Thread.sleep(Long.MaxValue) }.guarantee(done.set(true)).start
      _     <- IO(start.get())
      _     <- fiber.cancel
      value <- done.get
    } yield value must_== true

   "Main" should {
     "blocking IO is cancelable" in {
       Executors.unbound[IO].use { es =>
         val ec = ExecutionContext.fromExecutorService(es)
         implicit val cs: ContextShift[IO] = IO.contextShift(ec)

         implicit val dc = DualContext.fromExecutorService[IO](cs, es)

         testBlockingIOIsCancelable
       }.unsafeRunSync
     }
   }
}