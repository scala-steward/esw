package esw.gateway.api.clients

import akka.Done
import akka.stream.scaladsl.Source
import csw.params.events.{Event, EventKey}
import csw.prefix.models.Subsystem
import esw.gateway.api.EventApi
import esw.gateway.api.codecs.GatewayCodecs
import esw.gateway.api.protocol.PostRequest.{GetEvent, PublishEvent}
import esw.gateway.api.protocol.WebsocketRequest.{Subscribe, SubscribeWithPattern}
import esw.gateway.api.protocol._
import msocket.api.{Subscription, Transport}

import scala.concurrent.Future

class EventClient(postClient: Transport[PostRequest], websocketClient: Transport[WebsocketRequest])
    extends EventApi
    with GatewayCodecs {

  override def publish(event: Event): Future[Done]               = postClient.requestResponse[Done](PublishEvent(event))
  override def get(eventKeys: Set[EventKey]): Future[Set[Event]] = postClient.requestResponse[Set[Event]](GetEvent(eventKeys))

  override def subscribe(eventKeys: Set[EventKey], maxFrequency: Option[Int]): Source[Event, Subscription] =
    websocketClient.requestStream[Event](Subscribe(eventKeys, maxFrequency))

  override def pSubscribe(subsystem: Subsystem, maxFrequency: Option[Int], pattern: String): Source[Event, Subscription] =
    websocketClient.requestStream[Event](SubscribeWithPattern(subsystem, maxFrequency, pattern))
}
