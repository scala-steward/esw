package esw.ocs.impl.dsl.epic.internal.event

import java.util.concurrent.ScheduledExecutorService

import akka.actor.typed
import akka.stream.scaladsl.{Keep, Source, SourceQueueWithComplete}
import akka.stream.typed.scaladsl.ActorMaterializer
import akka.stream.{KillSwitch, KillSwitches, Materializer, OverflowStrategy}
import akka.{Done, NotUsed}
import esw.ocs.macros.StrandEc

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}

case class MockEvent(key: String, params: Map[String, Any]) {
  def add(field: String, value: Any): MockEvent = copy(params = params + (field -> value))
}

object MockEvent {
  def empty(key: String): MockEvent = MockEvent(key, Map.empty)
}

class MockEventService(implicit val actorSystem: typed.ActorSystem[_]) {

  var database: Map[String, MockEvent]                                     = Map.empty
  var subscriptions: Map[String, List[SourceQueueWithComplete[MockEvent]]] = Map.empty

  implicit val strandEc: StrandEc   = StrandEc()
  implicit val ec: ExecutionContext = strandEc.ec
  implicit val mat: Materializer    = ActorMaterializer()

  def get(key: String): Future[MockEvent] = Utils.timeout(2.seconds, strandEc.executorService).map { _ =>
    database.getOrElse(key, MockEvent.empty(key))
  }

  def publish(key: String, field: String, value: Any): Future[Done] =
    Utils.timeout(1.seconds, strandEc.executorService).flatMap { _ =>
      val event    = database.getOrElse(key, MockEvent.empty(key))
      val newEvent = event.add(field, value)
      database = database + (key -> newEvent)
      Future.traverse(subscriptions.getOrElse(key, List.empty))(_.offer(newEvent)).map(_ => Done)
    }

  def subscribe(key: String): Source[MockEvent, KillSwitch] = {
    val s: Source[MockEvent, SourceQueueWithComplete[MockEvent]]                        = Source.queue[MockEvent](1024, OverflowStrategy.dropHead)
    val (queue: SourceQueueWithComplete[MockEvent], stream: Source[MockEvent, NotUsed]) = s.preMaterialize()

    Future {
      val list = subscriptions.getOrElse(key, List.empty)
      subscriptions = subscriptions + (key -> (queue :: list))
    }

    stream.viaMat(KillSwitches.single)(Keep.right)
  }

}
object Utils {
  def timeout(duration: FiniteDuration, executorService: ScheduledExecutorService): Future[Unit] = {
    val p = Promise[Unit]()
    executorService.schedule(() => p.success(()), duration.length, duration.unit)
    p.future
  }
}
