package esw.gateway.api

import akka.Done
import akka.stream.scaladsl.Source
import csw.params.core.models.Subsystem
import csw.params.events.{Event, EventKey}
import esw.gateway.api.protocol.{EventError, EventServerUnavailable, GetEventError, InvalidMaxFrequency}

import scala.concurrent.Future

trait EventApi {
  def publish(event: Event): Future[Either[EventServerUnavailable.type, Done]]
  def get(eventKeys: Set[EventKey]): Future[Either[GetEventError, Set[Event]]]
  def subscribe(eventKeys: Set[EventKey], maxFrequency: Option[Int]): Source[Event, Future[Option[EventError]]]

  def pSubscribe(
      subsystem: Subsystem,
      maxFrequency: Option[Int],
      pattern: String
  ): Source[Event, Future[Option[InvalidMaxFrequency.type]]]

}
