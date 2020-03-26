package esw.gateway.server

import akka.actor.CoordinatedShutdown.UnknownReason
import csw.network.utils.Networks
import esw.ocs.testkit.EswTestKit

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class GatewayServiceTest extends EswTestKit {

  "GatewayWiring" must {
    "start the gateway server and register with location service | ESW-98" in {
      val _servicePort = 4005

      val wiring = new GatewayWiring(Some(_servicePort))
      import wiring.wiring._
      import wiring.wiring.cswWiring.actorRuntime

      val httpService             = wiring.httpService
      val (_, registrationResult) = Await.result(httpService.registeredLazyBinding, 5.seconds)

      Await.result(locationService.find(settings.httpConnection), 5.seconds).get.connection shouldBe settings.httpConnection

      val location = registrationResult.location
      location.uri.getHost shouldBe Networks().hostname
      location.connection shouldBe settings.httpConnection
      Await.result(actorRuntime.shutdown(UnknownReason), 5.seconds)
    }
  }
}
