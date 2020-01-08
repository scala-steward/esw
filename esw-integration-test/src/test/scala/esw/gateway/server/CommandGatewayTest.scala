package esw.gateway.server

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import csw.event.client.EventServiceFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.models.ComponentId
import csw.location.models.ComponentType.Assembly
import csw.params.commands.CommandResponse.{Accepted, Completed, Started}
import csw.params.commands.{CommandName, Setup}
import csw.params.core.models.ObsId
import csw.params.core.states.{CurrentState, StateName}
import csw.params.events.{Event, EventKey, EventName, SystemEvent}
import csw.prefix.models.Prefix
import esw.gateway.api.clients.ClientFactory
import esw.gateway.api.codecs.GatewayCodecs
import esw.ocs.testkit.EswTestKit
import esw.ocs.testkit.Service.{EventServer, Gateway}

import scala.concurrent.Future

class CommandGatewayTest extends EswTestKit(EventServer, Gateway) with GatewayCodecs {

  override def beforeAll(): Unit = {
    super.beforeAll()
    frameworkTestKit.spawnStandalone(ConfigFactory.load("standaloneAssembly.conf"))
  }

  "CommandApi" must {

    val prefix = Prefix("esw.test")
    "handle validate, oneway, submit, subscribe current state and queryFinal commands | ESW-223, ESW-100, ESW-91, ESW-216, ESW-86, CSW-81" in {
      val clientFactory = new ClientFactory(gatewayPostClient, gatewayWsClient)

      val eventService = new EventServiceFactory().make(HttpLocationServiceFactory.makeLocalClient)
      val eventKey     = EventKey(Prefix("tcs.filter.wheel"), EventName("setup-command-from-script"))

      val componentType      = Assembly
      val command            = Setup(prefix, CommandName("c1"), Some(ObsId("obsId")))
      val longRunningCommand = Setup(prefix, CommandName("long-running"), Some(ObsId("obsId")))
      val componentId        = ComponentId(prefix, componentType)
      val stateNames         = Set(StateName("stateName1"), StateName("stateName2"))
      val currentState1      = CurrentState(Prefix("esw.a.b"), StateName("stateName1"))
      val currentState2      = CurrentState(Prefix("esw.a.b"), StateName("stateName2"))

      val commandService                            = clientFactory.component(componentId)
      val currentStatesF: Future[Seq[CurrentState]] = commandService.subscribeCurrentState(stateNames).take(2).runWith(Sink.seq)
      Thread.sleep(1000)

      //validate
      commandService.validate(command).futureValue shouldBe an[Accepted]
      //oneway
      commandService.oneway(command).futureValue shouldBe an[Accepted]

      //submit-setup-command-subscription
      val testProbe    = TestProbe[Event]
      val subscription = eventService.defaultSubscriber.subscribeActorRef(Set(eventKey), testProbe.ref)
      subscription.ready().futureValue
      testProbe.expectMessageType[SystemEvent] // discard invalid event

      //submit the setup command
      val submitResponse = commandService.submit(longRunningCommand).futureValue
      submitResponse shouldBe a[Started]

      val actualSetupEvent: SystemEvent = testProbe.expectMessageType[SystemEvent]

      //assert the event which is publish in onSubmit handler of component
      actualSetupEvent.eventKey should ===(eventKey)

      //subscribe current state returns set of states successfully
      currentStatesF.futureValue.toSet should ===(Set(currentState1, currentState2))

      //queryFinal
      commandService.queryFinal(submitResponse.runId).futureValue should ===(Completed(submitResponse.runId))
    }

    "handle large websocket requests | CSW-81" in {
      val clientFactory = new ClientFactory(gatewayPostClient, gatewayWsClient)

      val componentType = Assembly
      val command       = Setup(prefix, CommandName("c1"), Some(ObsId("obsId")))
      val componentId   = ComponentId(prefix, componentType)
      val stateNames    = (1 to 10000).toSet[Int].map(x => StateName(s"stateName$x"))
      val currentState1 = CurrentState(Prefix("esw.a.b"), StateName("stateName1"))
      val currentState2 = CurrentState(Prefix("esw.a.b"), StateName("stateName2"))

      val commandService                            = clientFactory.component(componentId)
      val currentStatesF: Future[Seq[CurrentState]] = commandService.subscribeCurrentState(stateNames).take(2).runWith(Sink.seq)
      Thread.sleep(500)

      //oneway
      commandService.oneway(command).futureValue shouldBe an[Accepted]

      //subscribe current state returns set of states successfully
      currentStatesF.futureValue.toSet should ===(Set(currentState1, currentState2))
    }
  }
}
