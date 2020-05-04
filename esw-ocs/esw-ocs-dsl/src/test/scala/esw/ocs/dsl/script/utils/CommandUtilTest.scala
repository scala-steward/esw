package esw.ocs.dsl.script.utils

import java.util.concurrent.TimeUnit
import esw.ocs.api.BaseTestSuite
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import csw.command.client.messages.ComponentMessage
import csw.location.api.extensions.ActorExtension._
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import esw.commons.utils.location.EswLocationError.ResolveLocationFailed
import esw.commons.utils.location.LocationServiceUtil

import scala.concurrent.{ExecutionException, Future}

class CommandUtilTest extends ScalaTestWithActorTestKit with BaseTestSuite {
  private val locationServiceUtil = mock[LocationServiceUtil]
  private val prefix              = Prefix(ESW, "trombone")
  private val componentType       = ComponentType.Assembly
  private val connection          = AkkaConnection(ComponentId(prefix, componentType))
  private val testRef             = TestProbe[ComponentMessage].ref
  private val location            = AkkaLocation(connection, testRef.toURI)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(locationServiceUtil)
  }

  private val commandUtil = new CommandUtil(locationServiceUtil)
  "jResolveAkkaLocation" must {
    "return completion stage for akka location" in {
      when(locationServiceUtil.resolve(connection)).thenReturn(Future.successful(Right(location)))

      val completableLocation = commandUtil.jResolveAkkaLocation(prefix, componentType)

      completableLocation.toCompletableFuture.get(10, TimeUnit.SECONDS) shouldBe location
      verify(locationServiceUtil).resolve(connection)
    }

    "throw exception if location service returns error" in {
      when(locationServiceUtil.resolve(connection))
        .thenReturn(Future.successful(Left(ResolveLocationFailed("Error while resolving location"))))

      val message = intercept[ExecutionException](
        commandUtil.jResolveAkkaLocation(prefix, componentType).toCompletableFuture.get(10, TimeUnit.SECONDS)
      ).getLocalizedMessage
      "esw.commons.utils.location.EswLocationError$ResolveLocationFailed" shouldBe message
      verify(locationServiceUtil).resolve(connection)
    }
  }

  "jResolveComponentRef" must {
    "return completion stage for component ref" in {
      when(locationServiceUtil.resolve(connection)).thenReturn(Future.successful(Right(location)))

      val completableComponentRef = commandUtil.jResolveComponentRef(prefix, componentType)

      completableComponentRef.toCompletableFuture.get(10, TimeUnit.SECONDS) shouldBe testRef
      verify(locationServiceUtil).resolve(connection)
    }

    "throw exception if location service returns error" in {
      when(locationServiceUtil.resolve(connection))
        .thenReturn(Future.successful(Left(ResolveLocationFailed("Error while resolving location"))))

      val message = intercept[ExecutionException](
        commandUtil.jResolveComponentRef(prefix, componentType).toCompletableFuture.get(10, TimeUnit.SECONDS)
      ).getLocalizedMessage
      "esw.commons.utils.location.EswLocationError$ResolveLocationFailed" shouldBe message
      verify(locationServiceUtil).resolve(connection)
    }
  }
}
