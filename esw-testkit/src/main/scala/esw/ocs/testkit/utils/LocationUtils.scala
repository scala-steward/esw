package esw.ocs.testkit.utils

import akka.actor.typed.{ActorRef, ActorSystem, SpawnProtocol}
import csw.command.client.messages.sequencer.SequencerMsg
import csw.location.api.extensions.URIExtension.RichURI
import csw.location.api.models.Connection.{AkkaConnection, HttpConnection}
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType, HttpLocation}
import csw.location.api.scaladsl.LocationService
import csw.prefix.models.{Prefix, Subsystem}
import esw.ocs.api.actor.messages.SequenceComponentMsg
import esw.ocs.api.models.ObsMode

import scala.concurrent.duration.DurationDouble
import scala.concurrent.{ExecutionContext, Future}

trait LocationUtils extends BaseTestSuite {

  def locationService: LocationService
  implicit def actorSystem: ActorSystem[SpawnProtocol.Command]
  implicit def ec: ExecutionContext = actorSystem.executionContext

  def resolveHTTPLocationAsync(prefix: Prefix, componentType: ComponentType): Future[HttpLocation] =
    locationService.resolve(HttpConnection(ComponentId(prefix, componentType)), 5.seconds).map(_.value)

  def resolveHTTPLocation(prefix: Prefix, componentType: ComponentType): HttpLocation =
    resolveHTTPLocationAsync(prefix, componentType).futureValue

  def resolveSequencerLocation(prefix: Prefix): AkkaLocation = resolveAkkaLocation(prefix, ComponentType.Sequencer)

  def resolveSequencerLocation(subsystem: Subsystem, obsMode: ObsMode): AkkaLocation =
    resolveSequencerLocation(Prefix(subsystem, obsMode.name))

  def resolveSequencer(subsystem: Subsystem, obsMode: ObsMode): ActorRef[SequencerMsg] =
    resolveSequencerLocation(subsystem, obsMode).uri.toActorRef.unsafeUpcast[SequencerMsg]

  def resolveSequenceComponentLocation(prefix: Prefix): AkkaLocation =
    resolveAkkaLocation(prefix, ComponentType.SequenceComponent)

  def resolveAkkaLocation(prefix: Prefix, componentType: ComponentType): AkkaLocation =
    locationService
      .resolve(AkkaConnection(ComponentId(prefix, componentType)), 5.seconds)
      .futureValue
      .value

  def resolveSequenceComponent(prefix: Prefix): ActorRef[SequenceComponentMsg] =
    resolveSequenceComponentLocation(prefix).uri.toActorRef.unsafeUpcast[SequenceComponentMsg]

}
