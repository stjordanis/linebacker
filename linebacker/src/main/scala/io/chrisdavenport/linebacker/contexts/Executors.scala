package io.chrisdavenport.linebacker.contexts

import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory

import cats.effect.{Resource, Sync}
import cats.implicits._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ExecutorService, ForkJoinPool, ThreadFactory, Executors => E}

object Executors {

  import unsafe._

  /**
   * Recommended Pool For Non-CPU Load Blocking.
   * For example in a situation where you are
   * transitioning off a CPU loaded task and onto
   * a Hikari pool, you may want to back the
   * resource with the same number of threads.
   */
  def fixedPool[F[_]: Sync](n: Int): Resource[F, ExecutorService] =
    for {
      factory <- Resource.liftF(Sync[F].delay(E.defaultThreadFactory()))
      executor <- fixedPoolC(n)(factory)
    } yield executor

  def fixedPoolC[F[_]: Sync](n: Int)(threadFactory: ThreadFactory): Resource[F, ExecutorService] =
    executorServiceResource(fixedPoolExecutorUnsafe(n, threadFactory))

  /**
   * Constructs an unbound thread pool that will create
   * a new thread for each submitted job. This is useful
   * if you have a construct that is blocking but
   * self-manages the number of threads you can consume.
   */
  def unbound[F[_]: Sync]: Resource[F, ExecutorService] =
    for {
      factory <- Resource.liftF(unboundThreadFactory[F])
      executor <- unboundC[F](factory)
    } yield executor

  def unboundC[F[_]: Sync](threadFactory: ThreadFactory): Resource[F, ExecutorService] =
    executorServiceResource(unboundExecutorUnsafe(threadFactory))

  /**
   * A work stealing pool is often a useful blocking
   * pool for CPU bound blocking work that you want
   * to transition off the pool that is handling your
   * requests, generally set to the number of processors
   * to maximize the processor use. Perhaps subtracting
   * 1 as to maximize the other pool for handling
   * requests or other work.
   */
  def workStealingPool[F[_]: Sync](n: Int): Resource[F, ExecutorService] =
    workStealingPoolC(n)(ForkJoinPool.defaultForkJoinWorkerThreadFactory)

  def workStealingPoolC[F[_]: Sync](n: Int)(
      threadFactory: ForkJoinWorkerThreadFactory): Resource[F, ExecutorService] =
    executorServiceResource(workStealingPoolUnsafe(n, threadFactory))

  /**
   * Default Pool For Scala, optimized for forked work and then returning to a
   * main pool, generally ideal for your main event loop.
   */
  def forkJoinPool[F[_]: Sync](n: Int): Resource[F, ExecutorService] =
    forkJoinPoolC(n)(ForkJoinPool.defaultForkJoinWorkerThreadFactory)

  def forkJoinPoolC[F[_]: Sync](n: Int)(
      threadFactory: ForkJoinWorkerThreadFactory): Resource[F, ExecutorService] =
    executorServiceResource(forkJoinPoolUnsafe(n, threadFactory))

  private def executorServiceResource[F[_]: Sync](
      f: F[ExecutorService]): Resource[F, ExecutorService] =
    Resource.make[F, ExecutorService](f)(es => Sync[F].delay(es.shutdownNow).void)

  object unsafe {
    private[Executors] def unboundThreadFactory[F[_]](implicit F: Sync[F]): F[ThreadFactory] =
      F.delay {
        new ThreadFactory {
          private val counter = new AtomicLong(0L)

          def newThread(r: Runnable): Thread = {
            val th = new Thread(r)
            th.setName("linebacker-thread-" + counter.getAndIncrement.toString)
            th.setDaemon(true)
            th
          }
        }
      }

    def unboundExecutorUnsafe[F[_]: Sync](threadFactory: ThreadFactory): F[ExecutorService] = {
      //delay used to avoid eager evaluation in case that instance is ignored by the configurer

      Sync[F].delay {
        E.newCachedThreadPool(threadFactory)
      }
    }

    def fixedPoolExecutorUnsafe[F[_]: Sync](
        n: Int,
        threadFactory: ThreadFactory): F[ExecutorService] =
      Sync[F].delay { E.newFixedThreadPool(n, threadFactory) }

    def workStealingPoolUnsafe[F[_]: Sync](
        n: Int,
        threadFactory: ForkJoinWorkerThreadFactory): F[ExecutorService] = {

      Sync[F].delay {
        new ForkJoinPool(n, threadFactory, null, true)
      }
    }

    def forkJoinPoolUnsafe[F[_]: Sync](
        n: Int,
        threadFactory: ForkJoinWorkerThreadFactory): F[ExecutorService] = {

      Sync[F].delay {
        new ForkJoinPool(n, threadFactory, null, false)
      }
    }
  }
}
