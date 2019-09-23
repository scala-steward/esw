package esw.ocs.impl.internal

import akka.actor.typed.{ActorRef, ActorSystem, SpawnProtocol}
import csw.location.api.exceptions.OtherLocationIsRegistered
import csw.location.api.extensions.ActorExtension.RichActor
import csw.location.api.extensions.URIExtension.RichURI
import csw.location.api.scaladsl.LocationService
import csw.location.models.Connection.AkkaConnection
import csw.location.models.{AkkaLocation, AkkaRegistration, ComponentId, ComponentType}
import csw.params.core.models.Prefix
import esw.dsl.sequence_manager.LocationServiceUtil
import esw.ocs.api.protocol.RegistrationError
import esw.ocs.impl.messages.SequenceComponentMsg
import esw.ocs.impl.messages.SequenceComponentMsg.Stop

import scala.concurrent.Future
import scala.util.Random
import scala.util.control.NonFatal

class SequenceComponentRegistration(
    prefix: Prefix,
    _locationService: LocationService,
    sequenceComponentFactory: String => Future[ActorRef[SequenceComponentMsg]]
)(
    implicit override val actorSystem: ActorSystem[SpawnProtocol]
) extends LocationServiceUtil(_locationService) {

  def registerWithRetry(retryCount: Int): Future[Either[RegistrationError, AkkaLocation]] =
    registration().flatMap { akkaRegistration =>
      register(
        akkaRegistration,
        onFailure = {
          case OtherLocationIsRegistered(_) if retryCount > 0 =>
            //kill actor ref if registration fails. Retry attempt will create new actor ref
            akkaRegistration.actorRefURI.toActorRef.unsafeUpcast[SequenceComponentMsg] ! Stop
            registerWithRetry(retryCount - 1)
          case NonFatal(e) => Future.successful(Left(RegistrationError(e.getMessage)))
        }
      )
    }

  private def registration(): Future[AkkaRegistration] = {
    val name = s"${prefix.subsystem}_${Random.between(1, 100)}"
    sequenceComponentFactory(name).map { actorRef =>
      AkkaRegistration(AkkaConnection(ComponentId(name, ComponentType.SequenceComponent)), prefix, actorRef.toURI)
    }
  }
}
