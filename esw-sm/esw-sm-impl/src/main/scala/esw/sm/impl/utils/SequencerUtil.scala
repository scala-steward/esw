package esw.sm.impl.utils

import akka.actor.typed.ActorSystem
import csw.location.api.models.ComponentType.Sequencer
import csw.location.api.models.{AkkaLocation, ComponentId, Location}
import csw.prefix.models.Subsystem.ESW
import csw.prefix.models.{Prefix, Subsystem}
import esw.commons.extensions.FutureEitherExt.FutureEitherOps
import esw.commons.extensions.ListEitherExt.ListEitherOps
import esw.commons.utils.location.{EswLocationError, LocationServiceUtil}
import esw.ocs.api.SequencerApi
import esw.ocs.api.actor.client.SequencerApiFactory
import esw.ocs.api.models.ObsMode
import esw.ocs.api.protocol.ScriptError
import esw.ocs.api.protocol.SequenceComponentResponse.{SequencerLocation, Unhandled}
import esw.sm.api.protocol.CommonFailure.LocationServiceError
import esw.sm.api.protocol.ConfigureResponse.FailedToStartSequencers
import esw.sm.api.protocol.ShutdownSequencersPolicy.{AllSequencers, ObsModeSequencers, SingleSequencer, SubsystemSequencers}
import esw.sm.api.protocol.StartSequencerResponse.{LoadScriptError, SequenceComponentNotAvailable}
import esw.sm.api.protocol._
import esw.sm.impl.config.Sequencers

import scala.concurrent.{ExecutionContext, Future}

class SequencerUtil(locationServiceUtil: LocationServiceUtil, sequenceComponentUtil: SequenceComponentUtil)(implicit
    actorSystem: ActorSystem[_]
) {
  implicit private val ec: ExecutionContext = actorSystem.executionContext

  def startSequencers(obsMode: ObsMode, sequencers: Sequencers): Future[ConfigureResponse] =
    sequenceComponentUtil
      .getAllIdleSequenceComponentsFor(sequencers.subsystems)
      .flatMap {
        case Left(error) => Future.successful(error)
        case Right(idleSeqComps) =>
          mapSequencersToSequenceComponents(sequencers, idleSeqComps) match {
            case Left(error)                      => Future.successful(error)
            case Right(sequencerToSeqCompMapping) => startSequencersByMapping(obsMode, sequencerToSeqCompMapping)
          }
      }

  def restartSequencer(subSystem: Subsystem, obsMode: ObsMode): Future[RestartSequencerResponse] =
    locationServiceUtil
      .findSequencer(subSystem, obsMode)
      .flatMapToAdt(restartSequencer, e => LocationServiceError(e.msg))

  def shutdownSequencers(policy: ShutdownSequencersPolicy): Future[ShutdownSequencersResponse] =
    policy match {
      case SingleSequencer(subsystem, obsMode) => shutdownSequencersAndHandleErrors(getSequencer(subsystem, obsMode))
      case SubsystemSequencers(subsystem)      => shutdownSequencersAndHandleErrors(getSubsystemSequencers(subsystem))
      case ObsModeSequencers(obsMode)          => shutdownSequencersAndHandleErrors(getObsModeSequencers(obsMode))
      case AllSequencers                       => shutdownSequencersAndHandleErrors(getAllSequencers)
    }

  private[utils] def startSequencersByMapping(
      obsMode: ObsMode,
      mappings: Map[Subsystem, AkkaLocation]
  ): Future[ConfigureResponse] =
    parallel(mappings.toList) { mapping =>
      val (sequencerSubsystem, seqCompLocation) = mapping
      sequenceComponentUtil.loadScript(sequencerSubsystem, obsMode, seqCompLocation)
    }.mapToAdt(
      _ => ConfigureResponse.Success(ComponentId(Prefix(ESW, obsMode.name), Sequencer)),
      errors => FailedToStartSequencers(errors.map(_.msg).toSet)
    )

  // map sequencers to available seq comp for subsystem or ESW (fallback)
  private[utils] def mapSequencersToSequenceComponents(
      sequencers: Sequencers,
      seqCompLocations: List[AkkaLocation]
  ): Either[SequenceComponentNotAvailable, Map[Subsystem, AkkaLocation]] = {
    val subsystems = sequencers.subsystems
    var locations  = seqCompLocations
    val mapping = (for {
      subsystem       <- subsystems
      seqCompLocation <- findMatchingSeqComp(subsystem, locations)
    } yield {
      locations = locations.filterNot(_.equals(seqCompLocation))
      (subsystem, seqCompLocation)
    }).toMap

    val diff = subsystems.diff(mapping.keys.toList)
    if (diff.isEmpty) Right(mapping) else Left(SequenceComponentNotAvailable(diff: _*))
  }

  private def findMatchingSeqComp(subsystem: Subsystem, seqCompLocations: List[AkkaLocation]): Option[AkkaLocation] =
    seqCompLocations.find(_.prefix.subsystem == subsystem).orElse(seqCompLocations.find(_.prefix.subsystem == ESW))

  private def restartSequencer(akkaLocation: AkkaLocation): Future[RestartSequencerResponse] =
    createSequencerClient(akkaLocation).getSequenceComponent
      .flatMap(sequenceComponentUtil.restartScript(_).map {
        case SequencerLocation(location) => RestartSequencerResponse.Success(location.connection.componentId)
        case error: ScriptError          => LoadScriptError(error.msg)
        case Unhandled(_, _, msg)        => LoadScriptError(msg) // restart is unhandled in idle or shutting down state
      })

  private def getSequencer(subsystem: Subsystem, obsMode: ObsMode) =
    locationServiceUtil.findSequencer(subsystem, obsMode).mapRight(List(_))
  private def getSubsystemSequencers(subsystem: Subsystem) = locationServiceUtil.listAkkaLocationsBy(subsystem, Sequencer)
  private def getObsModeSequencers(obsMode: ObsMode)       = locationServiceUtil.listAkkaLocationsBy(obsMode.name, Sequencer)
  private def getAllSequencers                             = locationServiceUtil.listAkkaLocationsBy(Sequencer)

  private def shutdownSequencersAndHandleErrors(sequencers: Future[Either[EswLocationError, List[AkkaLocation]]]) =
    sequencers.flatMapRight(unloadScripts).mapToAdt(identity, locationErrorToShutdownSequencersResponse)

  private def locationErrorToShutdownSequencersResponse(err: EswLocationError) =
    err match {
      case _: EswLocationError.LocationNotFound => ShutdownSequencersResponse.Success
      case e: EswLocationError                  => LocationServiceError(e.msg)
    }

  // get sequence component from Sequencer and unload sequencer script
  private def unloadScript(sequencerLocation: AkkaLocation) =
    createSequencerClient(sequencerLocation).getSequenceComponent
      .flatMap(sequenceComponentUtil.unloadScript)
      .map(_ => ShutdownSequencersResponse.Success)

  private def unloadScripts(sequencerLocations: List[AkkaLocation]): Future[ShutdownSequencersResponse.Success.type] =
    Future.traverse(sequencerLocations)(unloadScript).map(_ => ShutdownSequencersResponse.Success)

  // Created in order to mock the behavior of sequencer API availability for unit test
  private[sm] def createSequencerClient(location: Location): SequencerApi = SequencerApiFactory.make(location)

  private def parallel[T, L, R](i: List[T])(f: T => Future[Either[L, R]]) = Future.traverse(i)(f).map(_.sequence)
}
