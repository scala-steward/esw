package esw.sm.api

import csw.prefix.models.{Prefix, Subsystem}
import esw.ocs.api.models.ObsMode
import esw.sm.api.protocol._

import scala.concurrent.Future

trait SequenceManagerApi {
  def configure(obsMode: ObsMode): Future[ConfigureResponse]
  def provision(): Future[ProvisionResponse]
  def getRunningObsModes: Future[GetRunningObsModesResponse]

  def startSequencer(subsystem: Subsystem, obsMode: ObsMode): Future[StartSequencerResponse]
  def restartSequencer(subsystem: Subsystem, obsMode: ObsMode): Future[RestartSequencerResponse]

  def shutdownSequencer(subsystem: Subsystem, obsMode: ObsMode): Future[ShutdownSequencersResponse]
  def shutdownSubsystemSequencers(subsystem: Subsystem): Future[ShutdownSequencersResponse]
  def shutdownObsModeSequencers(obsMode: ObsMode): Future[ShutdownSequencersResponse]
  def shutdownAllSequencers(): Future[ShutdownSequencersResponse]

  def spawnSequenceComponent(machine: Prefix, name: String): Future[SpawnSequenceComponentResponse]

  def shutdownSequenceComponent(prefix: Prefix): Future[ShutdownSequenceComponentResponse]
  def shutdownAllSequenceComponents(): Future[ShutdownSequenceComponentResponse]

  def getAgentStatus: Future[AgentStatusResponse]
}
