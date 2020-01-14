package esw.gateway.server.utils

import akka.actor.typed.ActorSystem
import csw.command.api.scaladsl.CommandService
import csw.location.api.scaladsl.LocationService
import csw.location.models.ComponentId
import esw.gateway.api.protocol.InvalidComponent
import esw.http.core.utils.{ComponentFactory, ComponentNotFoundException}
import esw.ocs.api.SequencerApi
import esw.ocs.impl.SequencerApiFactory

import scala.concurrent.Future

class Resolver(locationService: LocationService)(implicit typedSystem: ActorSystem[_]) {
  import typedSystem.executionContext

  private val componentFactory = new ComponentFactory(locationService)

  def commandService(componentId: ComponentId): Future[CommandService] =
    componentFactory.commandService(componentId).recover {
      case e: ComponentNotFoundException => throw InvalidComponent(e.getMessage)
    }

  def sequencerCommandService(componentId: ComponentId): Future[SequencerApi] =
    componentFactory.resolveLocation(componentId)(SequencerApiFactory.make).recover {
      case e: ComponentNotFoundException => throw InvalidComponent(e.getMessage)
    }

}
