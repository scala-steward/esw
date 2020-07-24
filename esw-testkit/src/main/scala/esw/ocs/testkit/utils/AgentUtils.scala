package esw.ocs.testkit.utils

import akka.actor.CoordinatedShutdown.UnknownReason
import akka.actor.typed.{ActorSystem, SpawnProtocol}
import com.typesafe.config.ConfigFactory
import csw.prefix.models.Subsystem.ESW
import csw.prefix.models.{Prefix, Subsystem}
import esw.agent.app.{AgentApp, AgentSettings, AgentWiring}

import scala.util.Random

trait AgentUtils {
  implicit def actorSystem: ActorSystem[SpawnProtocol.Command]

  private var agentWiring: Option[AgentWiring] = None
  lazy val agentSettings: AgentSettings        = AgentSettings.from(ConfigFactory.load())

  def spawnAgent(agentSettings: AgentSettings, subsystem: Subsystem = ESW): Prefix = {
    val agentPrefix = Prefix(subsystem, s"machine_${Random.nextInt().abs}")
    val wiring      = AgentWiring.make(agentPrefix, agentSettings, actorSystem)
    agentWiring = Some(wiring)
    AgentApp.start(agentPrefix, wiring)
    agentPrefix
  }

  def shutdownAgent(): Unit = agentWiring.foreach(_.actorRuntime.shutdown(UnknownReason))
}
