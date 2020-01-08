package esw.agent.client

import java.net.URI

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.Behaviors
import csw.location.api.scaladsl.LocationService
import csw.location.models.ComponentType.Machine
import csw.location.models.Connection.AkkaConnection
import csw.location.models.{AkkaLocation, ComponentId}
import csw.prefix.models.Prefix
import esw.agent.api.AgentCommand
import esw.agent.api.AgentCommand.SpawnCommand.SpawnSequenceComponent
import esw.agent.api.Response.Spawned
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.concurrent.duration.DurationLong

class AgentClientTest extends ScalaTestWithActorTestKit with WordSpecLike with Matchers with BeforeAndAfterAll {

  "make" should {
    "resolve the given prefix and return a new instance of AgentClient  | ESW-237" in {
      val locationService: LocationService = MockitoSugar.mock[LocationService]
      val prefix                           = Prefix("esw.test1")
      val akkaConnection                   = AkkaConnection(ComponentId(prefix, Machine))
      val agentLocation = AkkaLocation(
        akkaConnection,
        URI.create("akka://abc")
      )
      when(locationService.resolve(akkaConnection, 5.seconds)).thenReturn(
        Future.successful(
          Some(
            agentLocation
          )
        )
      )
      AgentClient.make(prefix, locationService).futureValue
    }
    "return a failed future when location service cant resolve agent  | ESW-237" in {
      val locationService: LocationService = MockitoSugar.mock[LocationService]
      val prefix                           = Prefix("esw.test1")
      val akkaConnection                   = AkkaConnection(ComponentId(prefix, Machine))
      when(locationService.resolve(akkaConnection, 5.seconds)).thenReturn(
        Future.successful(None)
      )
      val exception = intercept[RuntimeException](AgentClient.make(prefix, locationService).futureValue)
      exception.getCause.getMessage should ===(s"could not resolve $prefix")
    }
    "return a failed future when location service call fails  | ESW-237" in {
      val locationService: LocationService = MockitoSugar.mock[LocationService]
      val prefix                           = Prefix("esw.test1")
      val akkaConnection                   = AkkaConnection(ComponentId(prefix, Machine))
      when(locationService.resolve(akkaConnection, 5.seconds)).thenReturn(
        Future.failed(new RuntimeException("boom"))
      )
      val exception = intercept[RuntimeException](AgentClient.make(prefix, locationService).futureValue)
      exception.getCause.getMessage should ===(s"boom")
    }
  }

  "spawnSequenceComponent" should {
    "send SpawnSequenceComponent message to agent and return a future with agent response" in {
      val agentRef                = spawn(stubAgent)
      implicit val sch: Scheduler = system.scheduler
      val agentClient             = new AgentClient(agentRef)
      val prefix                  = Prefix("esw.test2")
      agentClient.spawnSequenceComponent(prefix).futureValue should ===(Spawned)
    }
  }

  private def stubAgent: Behaviors.Receive[AgentCommand] = Behaviors.receiveMessagePartial[AgentCommand] {
    case SpawnSequenceComponent(replyTo, _) =>
      replyTo ! Spawned
      Behaviors.same
  }
}
