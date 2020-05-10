package esw.http.core.utils

import java.net.URI

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import csw.location.api.models.Connection.{AkkaConnection, HttpConnection}
import csw.location.api.models.{AkkaLocation, ComponentId, HttpLocation}
import csw.location.api.scaladsl.LocationService
import esw.http.core.wiring.ActorRuntime
import org.mockito.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class ComponentFactoryTest extends AnyWordSpec with MockitoSugar with Matchers with BeforeAndAfterAll {
  private val actorSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "test")
  private val actorRuntime                                    = new ActorRuntime(actorSystem)
  import actorRuntime._

  private val locationService       = mock[LocationService]
  private val akkaComponentId       = mock[ComponentId]
  private val httpComponentId       = mock[ComponentId]
  private val akkaConnection        = AkkaConnection(akkaComponentId)
  private val httpConnection        = HttpConnection(httpComponentId)
  private val commandServiceFactory = mock[ICommandServiceFactory]

  private val akkaLocation = AkkaLocation(akkaConnection, new URI("actor-path"))
  private val httpLocation = HttpLocation(httpConnection, new URI("actor-path"))

  when(locationService.resolve(akkaConnection, 5.seconds)).thenReturn(Future.successful(Some(akkaLocation)))

  when(locationService.resolve(AkkaConnection(httpComponentId), 5.seconds)).thenReturn(Future.successful(None))
  when(locationService.resolve(httpConnection, 5.seconds)).thenReturn(Future.successful(Some(httpLocation)))

  override protected def afterAll(): Unit = {
    actorSystem.terminate
    actorSystem.whenTerminated.futureValue
  }

  "resolveLocation" must {
    "resolve akka components using location service | ESW-91" in {
      val componentFactory = new ComponentFactory(locationService, commandServiceFactory)
      componentFactory
        .resolveLocation(akkaComponentId)(_ shouldBe akkaLocation)
        .futureValue
    }

    "fallback to http location when akka component not registered | ESW-91, ESW-258" in {
      val componentFactory = new ComponentFactory(locationService, commandServiceFactory)
      componentFactory
        .resolveLocation(httpComponentId)(_ shouldBe httpLocation)
        .futureValue
    }
  }

  "commandService" must {
    "make instance of command service | ESW-91" in {
      val componentFactory = new ComponentFactory(locationService, commandServiceFactory)
      componentFactory.commandService(akkaComponentId)
      eventually(verify(commandServiceFactory).make(akkaLocation))
    }
  }
}
