package esw.sm.app

import java.nio.file.Path

import akka.actor.typed.SpawnProtocol.Spawn
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem, Props, SpawnProtocol}
import akka.util.Timeout
import csw.location.client.ActorSystemFactory
import esw.commons.Timeouts
import esw.commons.utils.location.LocationServiceUtil
import esw.http.core.wiring.CswWiring
import esw.sm.api.actor.client.SequenceManagerImpl
import esw.sm.api.actor.messages.SequenceManagerMsg
import esw.sm.impl.core.{SequenceManagerBehavior, SequenceManagerConfigParser}
import esw.sm.impl.utils.{AgentUtil, SequenceComponentUtil, SequencerUtil}

import scala.concurrent.Await

class SequenceManagerWiring(configPath: Path) {
  private lazy val actorSystem: ActorSystem[SpawnProtocol.Command] =
    ActorSystemFactory.remote(SpawnProtocol(), "sequencer-manager")
  private lazy val cswWiring: CswWiring      = new CswWiring(actorSystem)
  private lazy implicit val timeout: Timeout = Timeouts.DefaultTimeout
  import cswWiring._
  import cswWiring.actorRuntime._

  private lazy val locationServiceUtil   = new LocationServiceUtil(locationService)
  private lazy val agentUtil             = new AgentUtil(locationServiceUtil)
  private lazy val sequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, agentUtil)
  private lazy val sequencerUtil         = new SequencerUtil(locationServiceUtil, sequenceComponentUtil)

  private lazy val config =
    Await.result(new SequenceManagerConfigParser(configUtils).read(configPath, isLocal = true), Timeouts.DefaultTimeout).obsModes

  private lazy val sequenceManagerBehavior =
    new SequenceManagerBehavior(config, locationServiceUtil, sequencerUtil)(actorSystem)

  private lazy val sequenceManagerRef: ActorRef[SequenceManagerMsg] = Await.result(
    actorSystem ? { x: ActorRef[ActorRef[SequenceManagerMsg]] =>
      Spawn(sequenceManagerBehavior.init(), "sequence-manager", Props.empty, x)
    },
    Timeouts.DefaultTimeout
  )

  def start: SequenceManagerImpl = new SequenceManagerImpl(sequenceManagerRef)
}
