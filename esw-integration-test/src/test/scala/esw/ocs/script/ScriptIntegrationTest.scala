package esw.ocs.script

import akka.actor.Scheduler
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem, SpawnProtocol}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.alarm.client.AlarmServiceFactory
import csw.alarm.models.AlarmSeverity
import csw.alarm.models.Key.AlarmKey
import csw.command.client.messages.sequencer.{SequencerMsg, SubmitSequenceAndWait}
import csw.event.client.EventServiceFactory
import csw.location.api.extensions.URIExtension.RichURI
import csw.location.api.scaladsl.LocationService
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.params.commands.CommandResponse.{Completed, SubmitResponse}
import csw.params.commands.{CommandName, Sequence, Setup}
import csw.params.core.generics.KeyType.StringKey
import csw.params.core.generics.Parameter
import csw.params.core.models.Subsystem.NFIRAOS
import csw.params.core.models.{Id, Prefix}
import csw.params.events.{Event, EventKey, EventName, SystemEvent}
import csw.testkit.scaladsl.CSWService.{AlarmServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import csw.time.core.models.UTCTime
import esw.ocs.api.BaseTestSuite
import esw.ocs.api.models.StepStatus.Finished.Success
import esw.ocs.api.models.{Step, StepList}
import esw.ocs.api.protocol._
import esw.ocs.app.wiring.SequencerWiring
import esw.ocs.impl.internal.Timeouts
import esw.ocs.impl.messages.SequencerMessages._

import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble

class ScriptIntegrationTest extends ScalaTestFrameworkTestKit(EventServer, AlarmServer) with BaseTestSuite {

  import frameworkTestKit.mat

  implicit val actorSystem: ActorSystem[SpawnProtocol] = frameworkTestKit.actorSystem
  implicit val scheduler: Scheduler                    = actorSystem.scheduler

  private implicit val askTimeout: Timeout = Timeouts.DefaultTimeout

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds)

  private val ocsPackageId     = "esw"
  private val ocsObservingMode = "darknight"
  private val tcsPackageId     = "tcs"
  private val tcsObservingMode = "darknight"

  private var locationService: LocationService     = _
  private var ocsWiring: SequencerWiring           = _
  private var ocsSequencer: ActorRef[SequencerMsg] = _
  private var tcsWiring: SequencerWiring           = _
  private var tcsSequencer: ActorRef[SequencerMsg] = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    frameworkTestKit.spawnStandalone(ConfigFactory.load("standalone.conf"))
  }

  override def beforeEach(): Unit = {
    locationService = HttpLocationServiceFactory.makeLocalClient
    tcsWiring = new SequencerWiring(tcsPackageId, tcsObservingMode, None)
    tcsWiring.sequencerServer.start()
    tcsSequencer = tcsWiring.sequencerRef

    ocsWiring = new SequencerWiring(ocsPackageId, ocsObservingMode, None)
    ocsSequencer = ocsWiring.sequencerServer.start().rightValue.uri.toActorRef.unsafeUpcast[SequencerMsg]
  }

  override def afterEach(): Unit = {
    ocsWiring.sequencerServer.shutDown().futureValue
    tcsWiring.sequencerServer.shutDown().futureValue
  }

  "CswServices" must {
    "be able to send sequence to other Sequencer by resolving location through TestScript | ESW-195, ESW-119" in {
      val command             = Setup(Prefix("TCS.test"), CommandName("command-4"), None)
      val submitResponseProbe = TestProbe[SubmitResponse]
      val sequenceId          = Id()
      val sequence            = Sequence(sequenceId, Seq(command))

      ocsSequencer ! SubmitSequenceAndWait(sequence, submitResponseProbe.ref)

      val commandId = Id("testCommandIdString123")
      // This has to match with sequence created in TestScript -> handleSetupCommand("command-4")
      val assertableCommand =
        Setup(commandId, Prefix("TCS.test"), CommandName("command-3"), None, Set.empty)
      val steps            = List(Step(assertableCommand).copy(status = Success(Completed(commandId))))
      val expectedStepList = StepList(Id("testSequenceIdString123"), steps)
      Thread.sleep(1000)

      val actualStepList: Future[Option[StepList]] = tcsSequencer ? GetSequence
      // response received by irisSequencer
      submitResponseProbe.expectMessage(Completed(sequenceId))
      actualStepList.futureValue.get should ===(expectedStepList)
    }

    "be able to forward diagnostic mode to downstream components | ESW-118" in {
      val eventService = new EventServiceFactory().make(HttpLocationServiceFactory.makeLocalClient)
      val eventKey     = EventKey(Prefix("tcs.filter.wheel"), EventName("diagnostic-data"))

      val testProbe    = TestProbe[Event]
      val subscription = eventService.defaultSubscriber.subscribeActorRef(Set(eventKey), testProbe.ref)
      subscription.ready().futureValue
      testProbe.expectMessageType[SystemEvent] // discard invalid event

      //diagnosticMode
      val diagnosticModeParam: Parameter[_] = StringKey.make("mode").set("diagnostic")

      val diagnosticModeResF: Future[DiagnosticModeResponse] = ocsSequencer ? (DiagnosticMode(UTCTime.now(), "engineering", _))
      diagnosticModeResF.futureValue should ===(Ok)

      val expectedDiagEvent = testProbe.expectMessageType[SystemEvent]
      expectedDiagEvent.paramSet.head shouldBe diagnosticModeParam

      //operationsMode
      val operationsModeParam = StringKey.make("mode").set("operations")

      val operationsModeResF: Future[OperationsModeResponse] = ocsSequencer ? OperationsMode
      operationsModeResF.futureValue should ===(Ok)

      val expectedOpEvent = testProbe.expectMessageType[SystemEvent]
      expectedOpEvent.paramSet.head shouldBe operationsModeParam
    }

    "be able to forward GoOnline/GoOffline message to downstream components | ESW-236" in {
      val eventService = new EventServiceFactory().make(HttpLocationServiceFactory.makeLocalClient)
      val onlineKey    = EventKey(Prefix("tcs.filter.wheel"), EventName("online"))
      val offlineKey   = EventKey(Prefix("tcs.filter.wheel"), EventName("offline"))

      val testProbe          = TestProbe[Event]
      val onlineSubscription = eventService.defaultSubscriber.subscribeActorRef(Set(onlineKey), testProbe.ref)
      onlineSubscription.ready().futureValue
      testProbe.expectMessageType[SystemEvent] // discard invalid event

      val offlineSubscription = eventService.defaultSubscriber.subscribeActorRef(Set(offlineKey), testProbe.ref)
      offlineSubscription.ready().futureValue
      testProbe.expectMessageType[SystemEvent] // discard invalid event

      //goOffline
      val goOfflineResF: Future[GoOfflineResponse] = ocsSequencer ? GoOffline
      goOfflineResF.futureValue should ===(Ok)

      val expectedOfflineEvent = testProbe.expectMessageType[SystemEvent]
      expectedOfflineEvent.eventKey should ===(offlineKey)

      //goOnline
      val goOnlineResF: Future[GoOnlineResponse] = ocsSequencer ? GoOnline
      goOnlineResF.futureValue should ===(Ok)

      val expectedOnlineEvent = testProbe.expectMessageType[SystemEvent]
      expectedOnlineEvent.eventKey should ===(onlineKey)
    }

    "be able to set severity of sequencer alarms | ESW-125" in {
      val config            = ConfigFactory.parseResources("alarm_key.conf")
      val alarmAdminService = new AlarmServiceFactory().makeAdminApi(locationService)
      alarmAdminService.initAlarms(config, reset = true).futureValue

      val alarmKey = AlarmKey(NFIRAOS, "trombone", "tromboneAxisHighLimitAlarm")
      val command  = Setup(Prefix("NFIRAOS.test"), CommandName("set-alarm-severity"), None)
      val sequence = Sequence(command)

      val sequenceRes: Future[SubmitResponse] = ocsSequencer ? (SubmitSequenceAndWait(sequence, _))

      sequenceRes.futureValue should ===(Completed(sequence.runId))
      alarmAdminService.getCurrentSeverity(alarmKey).futureValue should ===(AlarmSeverity.Major)
    }

    "be able to get a published event | ESW-120" in {
      val eventService = new EventServiceFactory().make(HttpLocationServiceFactory.makeLocalClient)
      val publishF     = eventService.defaultPublisher.publish(SystemEvent(Prefix("TCS"), EventName("get.event")))
      publishF.futureValue

      val command  = Setup(Prefix("TCS"), CommandName("get-event"), None)
      val id       = Id()
      val sequence = Sequence(id, Seq(command))

      val submitResponse: Future[SubmitResponse] = ocsSequencer ? (SubmitSequenceAndWait(sequence, _))
      submitResponse.futureValue should ===(Completed(id))

      val successKey        = EventKey("TCS.get.success")
      val getPublishedEvent = eventService.defaultSubscriber.get(successKey).futureValue

      getPublishedEvent.isInvalid should ===(false)
    }
  }
}
