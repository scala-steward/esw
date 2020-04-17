package esw.sm.core

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout
import csw.location.api.models.ComponentType.Sequencer
import csw.location.api.models.{AkkaLocation, HttpLocation}
import csw.prefix.models.Subsystem.ESW
import esw.commons.Timeouts
import esw.commons.utils.location.LocationServiceUtil
import esw.sm.messages.ConfigureResponse._
import esw.sm.messages.SequenceManagerMsg._
import esw.sm.messages.{ConfigureResponse, SequenceManagerMsg}
import esw.sm.utils.{AgentUtil, SequenceComponentUtil, SequencerUtil}

import scala.async.Async.{async, await}
import scala.concurrent.Future

class SequenceManagerBehavior(locationService: LocationServiceUtil)(implicit val actorSystem: ActorSystem[_]) {
  import actorSystem.executionContext
  implicit val timeout: Timeout = Timeouts.DefaultTimeout

  private val sequenceComponentUtil = new SequenceComponentUtil(locationService, new AgentUtil(locationService))
  private val sequencerUtil         = new SequencerUtil(locationService, sequenceComponentUtil)

  def behavior(): Behavior[SequenceManagerMsg] = Behaviors.setup { _ =>
    idle() // initial behavior
  }

  def idle(): Behavior[SequenceManagerMsg] = Behaviors.receive { (ctx, msg) =>
    msg match {
      case Configure(observingMode, replyTo) => configure(observingMode, ctx.self); configuring(replyTo);
      case GetRunningObsModes(replyTo)       => getRunningObsModes.map(replyTo ! _); Behaviors.same
      case Cleanup(observingMode, replyTo)   => Behaviors.same
      case _                                 => Behaviors.same
    }
  }

  def configuring(replyTo: ActorRef[ConfigureResponse]): Behavior[SequenceManagerMsg] = Behaviors.receiveMessage {
    case ConfigurationCompleted(res) => replyTo ! res; idle()
    case GetRunningObsModes(replyTo) => getRunningObsModes.map(replyTo ! _); Behaviors.same
    case _                           => Behaviors.unhandled
  }

  def configure(obsMode: String, self: ActorRef[SequenceManagerMsg]): Future[Unit] =
    async {
      val configuredObsModes                   = await(getRunningObsModes) // filter master Seqs from all OCS Seqs
      val mayBeOcsMaster: Option[HttpLocation] = await(sequencerUtil.resolveMasterSequencerOf(obsMode))

      val response: ConfigureResponse = mayBeOcsMaster match {
        case Some(location) => await(useOcsMaster(location, obsMode))
        // todo : check all needed sequencer are idle. also handle case of partial start up
        case None => await(configureResources(obsMode, configuredObsModes))
      }

      self ! ConfigurationCompleted(response)
    }

  def useOcsMaster(location: HttpLocation, obsMode: String): Future[ConfigureResponse] = async {
    if (await(sequencerUtil.areSequencersIdle(extractSequencers(obsMode), obsMode))) Success(location)
    else ConfigurationFailure(s"Error: ${location.prefix} is already executing another sequence")
  }

  def configureResources(obsMode: String, configuredObsModes: Set[String]): Future[ConfigureResponse] = async {
    val requiredResources: Resources        = extractResources(obsMode)
    val configuredResources: Set[Resources] = configuredObsModes.map(extractResources)
    val areResourcesConflicting             = configuredResources.exists(_.conflictsWith(requiredResources))

    if (areResourcesConflicting) ConflictingResourcesWithRunningObsMode
    else await(sequencerUtil.startSequencers(obsMode, extractSequencers(obsMode)))
  }

  def getRunningObsModes: Future[Set[String]]        = locationService.listBy(ESW, Sequencer).map(_.map(getObsMode).toSet)
  def getObsMode(akkaLocation: AkkaLocation): String = akkaLocation.prefix.componentName

  def extractSequencers(obsMode: String): Sequencers = ???
  def extractResources(obsMode: String): Resources   = ???

}
