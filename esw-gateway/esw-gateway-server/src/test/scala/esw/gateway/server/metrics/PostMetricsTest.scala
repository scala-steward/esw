package esw.gateway.server.metrics

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.lonelyplanet.prometheus.PrometheusResponseTimeRecorder
import csw.command.api.messages.CommandServiceHttpMessage.Submit
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.Assembly
import csw.params.commands.{CommandName, Setup}
import csw.params.core.models.ObsId
import csw.params.events.{EventKey, EventName}
import csw.prefix.models.Prefix
import esw.gateway.api.codecs.GatewayCodecs
import esw.gateway.api.protocol.PostRequest
import esw.gateway.api.protocol.PostRequest.{ComponentCommand, GetEvent, SequencerCommand, createLabel}
import esw.gateway.server.CswWiringMocks
import esw.gateway.server.handlers.PostHandlerImpl
import esw.http.core.BaseTestSuite
import esw.ocs.api.protocol.SequencerPostRequest.Pause
import msocket.api.ContentType
import msocket.impl.post.{ClientHttpCodecs, PostRouteFactory}
import org.mockito.MockitoSugar
import org.scalatest.prop.Tables.Table

class PostMetricsTest extends BaseTestSuite with ScalatestRouteTest with GatewayCodecs with ClientHttpCodecs with MockitoSugar {

  override def clientContentType: ContentType = ContentType.Json

  private val cswCtxMocks = new CswWiringMocks()

  import cswCtxMocks._

  private val postHandlerImpl = new PostHandlerImpl(alarmApi, resolver, eventApi, loggingApi, adminService)
  private val postRoute       = new PostRouteFactory[PostRequest]("post-endpoint", postHandlerImpl).make(true)
  private val prefix          = Prefix("esw.test")

  private val defaultRegistry = PrometheusResponseTimeRecorder.DefaultRegistry
  private val command         = Setup(prefix, CommandName("c1"), Some(ObsId("obsId")))
  private val componentId     = ComponentId(prefix, Assembly)
  private val eventKey        = EventKey(prefix, EventName("event"))

  private def post[E: ToEntityMarshaller](entity: E): HttpRequest = Post("/post-endpoint", entity)

  private val labelNames = List(
    "msg",
    "hostname",
    "app_name",
    "command_msg",
    "sequencer_msg"
  )

  def labelValues(
      msg: String,
      hostName: String = "unknown",
      appName: String = "unknown",
      commandMsg: String = "",
      sequencerMsg: String = ""
  ): List[String] = List(msg, hostName, appName, commandMsg, sequencerMsg)

  Table(
    ("PostRequest", "Labels"),
    (ComponentCommand(componentId, Submit(command)), labelValues("ComponentCommand", commandMsg = "Submit")),
    (SequencerCommand(componentId, Pause), labelValues("SequencerCommand", sequencerMsg = "Pause")),
    (GetEvent(Set(eventKey)), labelValues("GetEvent"))
  ).foreach {
    case (request, labels) =>
      s"increment http counter on every ${createLabel(request)} request | ESW-197" in {
        runCounterTest(request, labels)
      }
  }

  private def getCounterValue(labelValues: List[String]): Double =
    defaultRegistry.getSampleValue("http_requests_total", labelNames.toArray, labelValues.toArray)

  private def runCounterTest(postRequest: PostRequest, labels: List[String]): Unit = {

    def counterValue = getCounterValue(labels)

    counterValue shouldBe 0
    (1 to 1).foreach(_ => post(postRequest) ~> postRoute)
    counterValue shouldBe 1
  }

}
