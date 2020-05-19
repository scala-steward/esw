package esw.http.core.wiring

import akka.actor.CoordinatedShutdown.UnknownReason
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives.{complete, get, path}
import akka.http.scaladsl.server.Route
import csw.location.api.exceptions.OtherLocationIsRegistered
import csw.location.api.models.{HttpRegistration, NetworkType}
import csw.network.utils.{Networks, SocketUtils}
import esw.ocs.testkit.EswTestKit

import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

class HttpServiceTest extends EswTestKit {

  private val route: Route =
    path("hello") {
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
      }
    }
  private val hostname                                 = Networks(NetworkType.Public.envKey).hostname
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 100.millis)

  "HttpService" must {
    "start the http server and register with location service | ESW-86 | CSW-96" in {
      val _servicePort = 4005
      val wiring       = ServerWiring.make(Some(_servicePort))
      import wiring._
      import wiring.cswWiring.actorRuntime
      val httpService = new HttpService(logger, locationService, route, settings, actorRuntime)

      SocketUtils.isAddressInUse(hostname, _servicePort) shouldBe false

      val (_, registrationResult) = httpService.registeredLazyBinding.futureValue

      locationService.find(settings.httpConnection).futureValue.get.connection shouldBe settings.httpConnection
      val location = registrationResult.location
      location.uri.getHost shouldBe hostname
      location.connection shouldBe settings.httpConnection
      SocketUtils.isAddressInUse(hostname, _servicePort) shouldBe true
      actorRuntime.shutdown(UnknownReason).futureValue
    }

    "not register with location service if server binding fails | ESW-86 | CSW-96" in {
      val _servicePort = 4452 // Location Service runs on this port
      val wiring       = ServerWiring.make(Some(_servicePort))
      import wiring._
      import wiring.cswWiring.actorRuntime
      val httpService     = new HttpService(logger, locationService, route, settings, actorRuntime)
      val address         = s"[/${hostname}:${_servicePort}]"
      val expectedMessage = s"Bind failed because of java.net.BindException: $address Address already in use"

      val bindException = intercept[Exception] { httpService.registeredLazyBinding.futureValue }

      bindException.getCause.getMessage shouldBe expectedMessage
      locationService.find(settings.httpConnection).futureValue shouldBe None
      actorRuntime.shutdown(UnknownReason).futureValue
    }

    "not start server if registration with location service fails | ESW-86" in {
      val _existingServicePort = 21212
      val _servicePort         = 4007
      val wiring               = ServerWiring.make(Some(_servicePort))
      import wiring._
      import wiring.cswWiring.actorRuntime
      val httpService = new HttpService(logger, locationService, route, settings, actorRuntime)
      locationService
        .register(HttpRegistration(settings.httpConnection, _existingServicePort, "", NetworkType.Public))
        .futureValue
      locationService.find(settings.httpConnection).futureValue.get.connection shouldBe settings.httpConnection

      SocketUtils.isAddressInUse(hostname, _servicePort) shouldBe false

      val otherLocationIsRegistered = intercept[Exception](httpService.registeredLazyBinding.futureValue)

      otherLocationIsRegistered.getCause shouldBe a[OtherLocationIsRegistered]
      SocketUtils.isAddressInUse(hostname, _servicePort) shouldBe false
      try actorRuntime.shutdown(UnknownReason).futureValue
      catch {
        case NonFatal(_) =>
      }
    }
  }
}
