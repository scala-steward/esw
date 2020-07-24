package esw.sm.impl.core

import java.net.URI

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.{ActorRef, ActorSystem, SpawnProtocol}
import csw.location.api.models.ComponentType._
import csw.location.api.models.Connection.{AkkaConnection, HttpConnection}
import csw.location.api.models.{AkkaLocation, ComponentId, HttpLocation}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem._
import esw.commons.utils.location.EswLocationError.{LocationNotFound, RegistrationListingFailed}
import esw.commons.utils.location.LocationServiceUtil
import esw.ocs.api.models.ObsMode
import esw.sm.api.actor.messages.SequenceManagerMsg._
import esw.sm.api.actor.messages.{SequenceManagerMsg, UnhandleableSequenceManagerMsg}
import esw.sm.api.models.AgentStatusResponses.{AgentSeqCompsStatus, SequenceComponentStatus}
import esw.sm.api.models.SequenceManagerState
import esw.sm.api.models.SequenceManagerState.{Idle, Processing}
import esw.sm.api.protocol.CommonFailure.{ConfigurationMissing, LocationServiceError}
import esw.sm.api.protocol.ConfigureResponse.{ConflictingResourcesWithRunningObsMode, Success}
import esw.sm.api.protocol.SpawnSequenceComponentResponse.SpawnSequenceComponentFailed
import esw.sm.api.protocol.StartSequencerResponse.{LoadScriptError, Started}
import esw.sm.api.protocol.{ShutdownSequenceComponentResponse, _}
import esw.sm.impl.config._
import esw.sm.impl.utils.{AgentUtil, SequenceComponentUtil, SequencerUtil}
import esw.testcommons.BaseTestSuite
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.Future
import scala.concurrent.duration.DurationLong

class SequenceManagerBehaviorTest extends BaseTestSuite with TableDrivenPropertyChecks {

  private implicit lazy val actorSystem: ActorSystem[SpawnProtocol.Command] =
    ActorSystem(SpawnProtocol(), "sequence-manager-system")

  private val darkNight                        = ObsMode("DarkNight")
  private val clearSkies                       = ObsMode("ClearSkies")
  private val randomObsMode                    = ObsMode("RandomObsMode")
  private val darkNightSequencers: Sequencers  = Sequencers(ESW, TCS)
  private val clearSkiesSequencers: Sequencers = Sequencers(ESW)
  private val config = SequenceManagerConfig(
    Map(
      darkNight  -> ObsModeConfig(Resources(Resource(NSCU), Resource(TCS)), darkNightSequencers),
      clearSkies -> ObsModeConfig(Resources(Resource(TCS), Resource(IRIS)), clearSkiesSequencers)
    )
  )

  private var provisionConfig                              = ProvisionConfig(Map(ESW -> 2, IRIS -> 2))
  private val provisionConfProvider                        = () => Future.successful(provisionConfig)
  private val locationServiceUtil: LocationServiceUtil     = mock[LocationServiceUtil]
  private val agentUtil: AgentUtil                         = mock[AgentUtil]
  private val sequencerUtil: SequencerUtil                 = mock[SequencerUtil]
  private val sequenceComponentUtil: SequenceComponentUtil = mock[SequenceComponentUtil]
  private val sequenceManagerBehavior = new SequenceManagerBehavior(
    config,
    provisionConfProvider,
    locationServiceUtil,
    agentUtil,
    sequencerUtil,
    sequenceComponentUtil
  )

  private lazy val smRef: ActorRef[SequenceManagerMsg] = actorSystem.systemActorOf(sequenceManagerBehavior.setup, "test_actor")

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds)

  override protected def beforeEach(): Unit = {
    provisionConfig = ProvisionConfig(Map(ESW -> 2, IRIS -> 2))
    reset(locationServiceUtil, sequencerUtil, sequenceComponentUtil, agentUtil)
  }

  "Configure" must {

    "transition sm from Idle -> Processing -> Idle state and return location of master sequencer | ESW-164, ESW-178, ESW-342" in {
      val componentId    = ComponentId(Prefix(ESW, darkNight.name), Sequencer)
      val configResponse = Success(componentId)
      when(locationServiceUtil.listAkkaLocationsBy(ESW, Sequencer)).thenReturn(future(1.seconds, Right(List.empty)))
      when(sequencerUtil.startSequencers(darkNight, darkNightSequencers))
        .thenReturn(Future.successful(configResponse))
      val configureProbe = TestProbe[ConfigureResponse]()

      // STATE TRANSITION: Idle -> Configure() -> Processing -> Idle
      assertState(Idle)
      smRef ! Configure(darkNight, configureProbe.ref)
      assertState(Processing)
      assertState(Idle)

      configureProbe.expectMessage(configResponse)
      verify(locationServiceUtil).listAkkaLocationsBy(ESW, Sequencer)
      verify(sequencerUtil).startSequencers(darkNight, darkNightSequencers)
    }

    "return LocationServiceError if location service fails to return running observation mode | ESW-164, ESW-178" in {
      when(locationServiceUtil.listAkkaLocationsBy(ESW, Sequencer))
        .thenReturn(Future.successful(Left(RegistrationListingFailed("Sequencer"))))

      val probe = TestProbe[ConfigureResponse]()
      smRef ! Configure(darkNight, probe.ref)

      probe.expectMessage(LocationServiceError("Sequencer"))
      verify(locationServiceUtil).listAkkaLocationsBy(ESW, Sequencer)
    }

    "return ConflictingResourcesWithRunningObsMode when required resources are already in use | ESW-169, ESW-168, ESW-170, ESW-179, ESW-178" in {
      // this simulates that ClearSkies observation is running
      val akkaLocation = AkkaLocation(AkkaConnection(ComponentId(Prefix(ESW, clearSkies.name), Sequencer)), new URI("uri"))
      when(locationServiceUtil.listAkkaLocationsBy(ESW, Sequencer)).thenReturn(Future.successful(Right(List(akkaLocation))))
      val probe = TestProbe[ConfigureResponse]()

      // r2 is a conflicting resource between DarkNight and ClearSkies observations
      smRef ! Configure(darkNight, probe.ref)

      probe.expectMessage(ConflictingResourcesWithRunningObsMode(Set(clearSkies)))
      verify(locationServiceUtil).listAkkaLocationsBy(ESW, Sequencer)
      verify(sequencerUtil, times(0)).startSequencers(darkNight, darkNightSequencers)
    }

    "return ConfigurationMissing error when config for given obsMode is missing | ESW-164, ESW-178" in {
      val akkaLocation = AkkaLocation(AkkaConnection(ComponentId(Prefix(ESW, randomObsMode.name), Sequencer)), new URI("uri"))
      when(locationServiceUtil.listAkkaLocationsBy(ESW, Sequencer)).thenReturn(Future.successful(Right(List(akkaLocation))))
      val probe = TestProbe[ConfigureResponse]()

      smRef ! Configure(randomObsMode, probe.ref)

      probe.expectMessage(ConfigurationMissing(randomObsMode))
      verify(locationServiceUtil).listAkkaLocationsBy(ESW, Sequencer)
    }
  }

  "StartSequencer" must {
    "transition sm from Idle -> Processing -> Idle state and return componentId of started sequencer | ESW-176, ESW-342" in {
      val componentId    = ComponentId(Prefix(ESW, darkNight.name), Sequencer)
      val httpConnection = HttpConnection(componentId)

      when(sequenceComponentUtil.loadScript(ESW, darkNight)).thenReturn(future(1.second, Started(componentId)))
      when(locationServiceUtil.find(httpConnection)).thenReturn(futureLeft(LocationNotFound("error")))

      val startSequencerResponseProbe = TestProbe[StartSequencerResponse]()

      // STATE TRANSITION: Idle -> StartSequencer -> Processing -> Idle
      assertState(Idle)
      smRef ! StartSequencer(ESW, darkNight, startSequencerResponseProbe.ref)
      assertState(Processing)
      assertState(Idle)

      startSequencerResponseProbe.expectMessage(StartSequencerResponse.Started(componentId))
      verify(sequenceComponentUtil).loadScript(ESW, darkNight)
      verify(locationServiceUtil).find(httpConnection)
    }

    "return AlreadyRunning if sequencer for given obs mode is already running | ESW-176" in {
      val componentId    = ComponentId(Prefix(ESW, darkNight.name), Sequencer)
      val httpConnection = HttpConnection(componentId)
      val httpLocation   = HttpLocation(httpConnection, new URI("uri"))

      when(locationServiceUtil.find(httpConnection))
        .thenReturn(futureRight(httpLocation))

      val startSequencerResponseProbe = TestProbe[StartSequencerResponse]()

      smRef ! StartSequencer(ESW, darkNight, startSequencerResponseProbe.ref)

      startSequencerResponseProbe.expectMessage(StartSequencerResponse.AlreadyRunning(componentId))
      verify(sequenceComponentUtil, never).loadScript(ESW, darkNight)
      verify(locationServiceUtil).find(httpConnection)
    }

    "return Error if start sequencer returns error | ESW-176" in {
      val componentId           = ComponentId(Prefix(ESW, darkNight.name), Sequencer)
      val httpConnection        = HttpConnection(componentId)
      val expectedErrorResponse = LoadScriptError("error")

      when(locationServiceUtil.find(httpConnection))
        .thenReturn(futureLeft(LocationNotFound("error")))
      when(sequenceComponentUtil.loadScript(ESW, darkNight)).thenReturn(Future.successful(expectedErrorResponse))

      val startSequencerResponseProbe = TestProbe[StartSequencerResponse]()

      smRef ! StartSequencer(ESW, darkNight, startSequencerResponseProbe.ref)

      startSequencerResponseProbe.expectMessage(expectedErrorResponse)
      verify(sequenceComponentUtil).loadScript(ESW, darkNight)
      verify(locationServiceUtil).find(httpConnection)
    }
  }

  "ShutdownSequencer" must {
    val responseProbe = TestProbe[ShutdownSequencersResponse]()
    val shutdownMsg   = ShutdownSequencer(ESW, darkNight, responseProbe.ref)
    s"transition sm from Idle -> Processing -> Idle state and stop| ESW-326, ESW-345, ESW-166, ESW-324, ESW-342, ESW-351" in {
      when(sequencerUtil.shutdownSequencer(ESW, darkNight)).thenReturn(future(1.seconds, ShutdownSequencersResponse.Success))

      // STATE TRANSITION: Idle -> ShutdownSequencers -> Processing -> Idle
      assertState(Idle)
      smRef ! shutdownMsg
      assertState(Processing)
      assertState(Idle)

      responseProbe.expectMessage(ShutdownSequencersResponse.Success)
      verify(sequencerUtil).shutdownSequencer(ESW, darkNight)
    }

    s"return LocationServiceError if location service fails | ESW-326, ESW-345, ESW-166, ESW-324, ESW-351" in {
      val err = LocationServiceError("error")
      when(sequencerUtil.shutdownSequencer(ESW, darkNight)).thenReturn(Future.successful(err))

      smRef ! shutdownMsg
      responseProbe.expectMessage(err)

      verify(sequencerUtil).shutdownSequencer(ESW, darkNight)
    }
  }

  "ShutdownSubsystemSequencers" must {
    val responseProbe = TestProbe[ShutdownSequencersResponse]()
    val shutdownMsg   = ShutdownSubsystemSequencers(ESW, responseProbe.ref)
    s"transition sm from Idle -> Processing -> Idle state and stop | ESW-326, ESW-345, ESW-166, ESW-324, ESW-342, ESW-351" in {
      when(sequencerUtil.shutdownSubsystemSequencers(ESW)).thenReturn(future(1.seconds, ShutdownSequencersResponse.Success))

      // STATE TRANSITION: Idle -> ShutdownSequencers -> Processing -> Idle
      assertState(Idle)
      smRef ! shutdownMsg
      assertState(Processing)
      assertState(Idle)

      responseProbe.expectMessage(ShutdownSequencersResponse.Success)
      verify(sequencerUtil).shutdownSubsystemSequencers(ESW)
    }

    s"return LocationServiceError if location service fails for | ESW-326, ESW-345, ESW-166, ESW-324, ESW-351" in {
      val err = LocationServiceError("error")
      when(sequencerUtil.shutdownSubsystemSequencers(ESW)).thenReturn(Future.successful(err))

      smRef ! shutdownMsg
      responseProbe.expectMessage(err)

      verify(sequencerUtil).shutdownSubsystemSequencers(ESW)
    }
  }

  "ShutdownObsModeSequencers" must {
    val responseProbe = TestProbe[ShutdownSequencersResponse]()
    val shutdownMsg   = ShutdownObsModeSequencers(darkNight, responseProbe.ref)
    s"transition sm from Idle -> Processing -> Idle state and stop | ESW-326, ESW-345, ESW-166, ESW-324, ESW-342, ESW-351" in {
      when(sequencerUtil.shutdownObsModeSequencers(darkNight)).thenReturn(future(1.seconds, ShutdownSequencersResponse.Success))

      // STATE TRANSITION: Idle -> ShutdownSequencers -> Processing -> Idle
      assertState(Idle)
      smRef ! shutdownMsg
      assertState(Processing)
      assertState(Idle)

      responseProbe.expectMessage(ShutdownSequencersResponse.Success)
      verify(sequencerUtil).shutdownObsModeSequencers(darkNight)
    }

    s"return LocationServiceError if location service fails | ESW-326, ESW-345, ESW-166, ESW-324, ESW-351" in {
      val err = LocationServiceError("error")
      when(sequencerUtil.shutdownObsModeSequencers(darkNight)).thenReturn(Future.successful(err))

      smRef ! shutdownMsg
      responseProbe.expectMessage(err)

      verify(sequencerUtil).shutdownObsModeSequencers(darkNight)
    }
  }

  "ShutdownAllSequencers" must {
    val responseProbe = TestProbe[ShutdownSequencersResponse]()
    val shutdownMsg   = ShutdownAllSequencers(responseProbe.ref)
    s"transition sm from Idle -> Processing -> Idle state and stop | ESW-326, ESW-345, ESW-166, ESW-324, ESW-342, ESW-351" in {
      when(sequencerUtil.shutdownAllSequencers()).thenReturn(future(1.seconds, ShutdownSequencersResponse.Success))

      // STATE TRANSITION: Idle -> ShutdownSequencers -> Processing -> Idle
      assertState(Idle)
      smRef ! shutdownMsg
      assertState(Processing)
      assertState(Idle)

      responseProbe.expectMessage(ShutdownSequencersResponse.Success)
      verify(sequencerUtil).shutdownAllSequencers()
    }

    s"return LocationServiceError if location service fails | ESW-326, ESW-345, ESW-166, ESW-324, ESW-351" in {
      val err = LocationServiceError("error")
      when(sequencerUtil.shutdownAllSequencers()).thenReturn(Future.successful(err))

      smRef ! shutdownMsg
      responseProbe.expectMessage(err)

      verify(sequencerUtil).shutdownAllSequencers()
    }
  }

  "RestartSequencer" must {
    "transition sm from Idle -> Processing -> Idle state and return success on restart | ESW-327, ESW-342" in {
      val prefix      = Prefix(ESW, darkNight.name)
      val componentId = ComponentId(prefix, Sequencer)

      when(sequencerUtil.restartSequencer(ESW, darkNight))
        .thenReturn(future(1.seconds, RestartSequencerResponse.Success(componentId)))

      val restartSequencerResponseProbe = TestProbe[RestartSequencerResponse]()

      // STATE TRANSITION: Idle -> RestartSequencer -> Processing -> Idle
      assertState(Idle)
      smRef ! RestartSequencer(ESW, darkNight, restartSequencerResponseProbe.ref)
      assertState(Processing)
      assertState(Idle)

      restartSequencerResponseProbe.expectMessage(RestartSequencerResponse.Success(componentId))
      verify(sequencerUtil).restartSequencer(ESW, darkNight)
    }

    val errors = Table(
      ("errorName", "error", "process"),
      ("LocationServiceError", LocationServiceError("location service error"), "stop"),
      ("LoadScriptError", LoadScriptError("load script failed"), "start")
    )

    forAll(errors) { (errorName, error, process) =>
      s"return $errorName if $errorName encountered while sequencer $process | ESW-327" in {
        when(sequencerUtil.restartSequencer(ESW, darkNight))
          .thenReturn(future(1.seconds, error))

        val restartSequencerResponseProbe = TestProbe[RestartSequencerResponse]()

        smRef ! RestartSequencer(ESW, darkNight, restartSequencerResponseProbe.ref)
        restartSequencerResponseProbe.expectMessage(error)

        verify(sequencerUtil).restartSequencer(ESW, darkNight)
      }
    }
  }

  "ShutdownSequenceComponents" must {
    "transition sm from Idle -> Processing -> Idle state and return success on shutdown | ESW-338, ESW-342, ESW-351" in {
      val prefix = Prefix(ESW, "primary")

      when(sequenceComponentUtil.shutdownSequenceComponent(prefix))
        .thenReturn(future(1.second, ShutdownSequenceComponentResponse.Success))

      val shutdownSequenceComponentResponseProbe = TestProbe[ShutdownSequenceComponentResponse]()

      // STATE TRANSITION: Idle -> ShutdownSequenceComponents -> Processing -> Idle
      assertState(Idle)
      smRef ! ShutdownSequenceComponent(prefix, shutdownSequenceComponentResponseProbe.ref)
      assertState(Processing)
      assertState(Idle)

      shutdownSequenceComponentResponseProbe.expectMessage(ShutdownSequenceComponentResponse.Success)
      verify(sequenceComponentUtil).shutdownSequenceComponent(prefix)
    }

    "return Success when shutting down all sequence components | ESW-346, ESW-351" in {
      when(sequenceComponentUtil.shutdownAllSequenceComponents())
        .thenReturn(Future.successful(ShutdownSequenceComponentResponse.Success))

      val shutdownSequenceComponentResponseProbe = TestProbe[ShutdownSequenceComponentResponse]()

      smRef ! ShutdownAllSequenceComponents(shutdownSequenceComponentResponseProbe.ref)
      shutdownSequenceComponentResponseProbe.expectMessage(ShutdownSequenceComponentResponse.Success)

      verify(sequenceComponentUtil).shutdownAllSequenceComponents()
    }

    "return LocationServiceError if LocationServiceError encountered while shutting down sequence components | ESW-338,ESW-346, ESW-351" in {
      val prefix = Prefix(ESW, "primary")
      val error  = LocationServiceError("location service error")

      when(sequenceComponentUtil.shutdownSequenceComponent(prefix)).thenReturn(Future.successful(error))
      val shutdownSequenceComponentResponseProbe = TestProbe[ShutdownSequenceComponentResponse]()

      smRef ! ShutdownSequenceComponent(prefix, shutdownSequenceComponentResponseProbe.ref)
      shutdownSequenceComponentResponseProbe.expectMessage(error)

      verify(sequenceComponentUtil).shutdownSequenceComponent(prefix)
    }
  }

  "SpawnSequenceComponent" must {
    "transition sm from Idle -> Processing -> Idle state and return success when spawned | ESW-337, ESW-342" in {
      val seqCompName = "seq_comp"
      val machine     = Prefix(ESW, "primary")
      val componentId = ComponentId(Prefix(ESW, seqCompName), SequenceComponent)
      when(agentUtil.spawnSequenceComponent(machine, seqCompName))
        .thenReturn(future(1.second, SpawnSequenceComponentResponse.Success(componentId)))

      val spawnSequenceComponentProbe = TestProbe[SpawnSequenceComponentResponse]()

      // STATE TRANSITION: Idle -> SpawnSequenceComponent -> Processing -> Idle
      assertState(Idle)
      smRef ! SpawnSequenceComponent(machine, seqCompName, spawnSequenceComponentProbe.ref)
      assertState(Processing)
      assertState(Idle)

      spawnSequenceComponentProbe.expectMessage(SpawnSequenceComponentResponse.Success(componentId))
      verify(agentUtil).spawnSequenceComponent(machine, seqCompName)
    }

    "return LocationServiceError if location service gives error | ESW-337" in {
      val seqCompName = "seq_comp"
      val agent       = Prefix(ESW, "primary")
      when(agentUtil.spawnSequenceComponent(agent, seqCompName))
        .thenReturn(Future.successful(LocationServiceError("location service error")))

      val spawnSequenceComponentProbe = TestProbe[SpawnSequenceComponentResponse]()

      smRef ! SpawnSequenceComponent(agent, seqCompName, spawnSequenceComponentProbe.ref)
      spawnSequenceComponentProbe.expectMessage(LocationServiceError("location service error"))

      verify(agentUtil).spawnSequenceComponent(agent, seqCompName)
    }

    "return SpawnSequenceComponentFailed if agent fails to spawn sequence component | ESW-337" in {
      val seqCompName = "seq_comp"
      val agent       = Prefix(ESW, "primary")
      when(agentUtil.spawnSequenceComponent(agent, seqCompName))
        .thenReturn(Future.successful(SpawnSequenceComponentFailed("spawning failed")))

      val spawnSequenceComponentProbe = TestProbe[SpawnSequenceComponentResponse]()

      smRef ! SpawnSequenceComponent(agent, seqCompName, spawnSequenceComponentProbe.ref)
      spawnSequenceComponentProbe.expectMessage(SpawnSequenceComponentFailed("spawning failed"))

      verify(agentUtil).spawnSequenceComponent(agent, seqCompName)
    }
  }

  "GetAllAgentStatus" must {
    "return agent status successfully | ESW-349" in {
      val eswMachine1 = ComponentId(Prefix(ESW, "machine1"), Machine)
      val eswMachine2 = ComponentId(Prefix(ESW, "machine2"), Machine)
      val tcsMachine1 = ComponentId(Prefix(TCS, "machine1"), Machine)

      val eswSeqComp1 = ComponentId(Prefix(ESW, "primary"), SequenceComponent)
      val eswSeqComp2 = ComponentId(Prefix(ESW, "secondary"), SequenceComponent)
      val tcsSeqComp1 = ComponentId(Prefix(TCS, "primary"), SequenceComponent)

      val eswMachine1SeqComps =
        List(
          SequenceComponentStatus(eswSeqComp1, Some(akkaLocation(ComponentId(Prefix(ESW, "darkNight"), Sequencer)))),
          SequenceComponentStatus(eswSeqComp2, None)
        )
      val tcsMachine1SeqComps =
        List(SequenceComponentStatus(tcsSeqComp1, Some(akkaLocation(ComponentId(Prefix(TCS, "darkNight"), Sequencer)))))

      val expectedResponse = AgentStatusResponse.Success(
        List(
          AgentSeqCompsStatus(eswMachine1, eswMachine1SeqComps),
          AgentSeqCompsStatus(eswMachine2, List.empty),
          AgentSeqCompsStatus(tcsMachine1, tcsMachine1SeqComps)
        )
      )

      when(agentUtil.getAllAgentStatus).thenReturn(Future.successful(expectedResponse))

      val getAgentStatusResponseProbe = TestProbe[AgentStatusResponse]()

      assertState(Idle)
      smRef ! GetAllAgentStatus(getAgentStatusResponseProbe.ref)
      assertState(Idle)

      getAgentStatusResponseProbe.expectMessage(expectedResponse)

      verify(agentUtil).getAllAgentStatus
    }

    "return LocationServiceError if location service gives error | ESW-349" in {
      when(agentUtil.getAllAgentStatus).thenReturn(Future.successful(LocationServiceError("error")))

      val getAgentStatusResponseProbe = TestProbe[AgentStatusResponse]()

      smRef ! GetAllAgentStatus(getAgentStatusResponseProbe.ref)

      getAgentStatusResponseProbe.expectMessage(LocationServiceError("error"))

      verify(agentUtil).getAllAgentStatus
    }
  }

  "provision" must {
    "transition from Idle -> Processing -> Idle and return provision success | ESW-346" in {
      when(agentUtil.provision(provisionConfig)).thenReturn(future(1.second, ProvisionResponse.Success))
      val provisionResponseProbe = TestProbe[ProvisionResponse]()

      assertState(Idle)
      smRef ! Provision(provisionResponseProbe.ref)
      assertState(Processing)
      assertState(Idle)

      verify(agentUtil).provision(provisionConfig)
      provisionResponseProbe.expectMessage(ProvisionResponse.Success)
    }

    "read updated configuration on every provision msg | ESW-346" in {
      when(agentUtil.provision(provisionConfig)).thenReturn(future(1.second, ProvisionResponse.Success))
      val provisionResponseProbe1 = TestProbe[ProvisionResponse]()
      smRef ! Provision(provisionResponseProbe1.ref)
      assertState(Idle)

      verify(agentUtil).provision(provisionConfig)
      provisionResponseProbe1.expectMessage(ProvisionResponse.Success)

      // update provision config
      val newProvisionConfig = ProvisionConfig(Map(IRIS -> 2, TCS -> 4))
      provisionConfig = newProvisionConfig

      val provisionResponseProbe2 = TestProbe[ProvisionResponse]()
      when(agentUtil.provision(newProvisionConfig)).thenReturn(future(1.second, ProvisionResponse.Success))

      smRef ! Provision(provisionResponseProbe2.ref)
      assertState(Idle)

      verify(agentUtil).provision(newProvisionConfig)
      provisionResponseProbe2.expectMessage(ProvisionResponse.Success)
    }

    "give ConfigurationFailure if config provider gives error | ESW-346" in {
      val errorMsg = "config reading failed"
      val provider = () => Future.failed(new RuntimeException(errorMsg))
      val smBeh =
        new SequenceManagerBehavior(config, provider, locationServiceUtil, agentUtil, sequencerUtil, sequenceComponentUtil)
      val smRef: ActorRef[SequenceManagerMsg] = actorSystem.systemActorOf(smBeh.setup, "test_actor1")

      val provisionResponseProbe = TestProbe[ProvisionResponse]()
      smRef ! Provision(provisionResponseProbe.ref)
      assertState(Idle)

      provisionResponseProbe.expectMessage(ProvisionResponse.ConfigurationFailure(errorMsg))
    }

    "return ProvisionResponse given by agentUtil.provision | ESW-346" in {
      val provisionResponse = ProvisionResponse.NoMachineFoundForSubsystems(Set(ESW))
      when(agentUtil.provision(provisionConfig)).thenReturn(Future.successful(provisionResponse))

      val provisionResponseProbe = TestProbe[ProvisionResponse]()
      smRef ! Provision(provisionResponseProbe.ref)
      assertState(Idle)
      provisionResponseProbe.expectMessage(provisionResponse)
    }
  }

  "Processing -> Unhandled" must {
    "return Unhandled if msg is unhandled in processing state | ESW-349" in {
      // hold configure completion by delay of 60 seconds. So SM will remain in processing state
      when(locationServiceUtil.listAkkaLocationsBy(ESW, Sequencer)).thenReturn(future(60.seconds, Right(List.empty)))

      val configureProbe = TestProbe[ConfigureResponse]()

      assertState(Idle)
      smRef ! Configure(darkNight, configureProbe.ref)

      // assert that SM is in Processing state
      assertState(Processing)

      // Assert that following msgs are getting unhandled response back in processing state
      assertUnhandled(
        Processing,
        ShutdownAllSequencers,
        Configure(clearSkies, _),
        ShutdownSequencer(ESW, darkNight, _),
        ShutdownObsModeSequencers(clearSkies, _),
        ShutdownSubsystemSequencers(ESW, _),
        StartSequencer(ESW, darkNight, _),
        RestartSequencer(ESW, darkNight, _),
        SpawnSequenceComponent(Prefix(ESW, "machine1"), "primary", _),
        ShutdownSequenceComponent(Prefix(ESW, "primary"), _),
        ShutdownAllSequenceComponents,
        Provision
      )
    }
  }

  private def assertUnhandled[T >: Unhandled <: SmResponse](
      state: SequenceManagerState,
      msg: ActorRef[T] => UnhandleableSequenceManagerMsg
  ): Unit = {
    val probe                  = TestProbe[T]()
    val sequenceManagerMessage = msg(probe.ref)
    smRef ! sequenceManagerMessage
    probe.expectMessage(Unhandled(state.entryName, sequenceManagerMessage.getClass.getSimpleName))
  }

  private def assertUnhandled[T >: Unhandled <: SmResponse](
      state: SequenceManagerState,
      msgs: (ActorRef[T] => UnhandleableSequenceManagerMsg)*
  ): Unit =
    msgs.foreach(assertUnhandled(state, _))

  private def assertState(state: SequenceManagerState) = {
    val stateProbe = TestProbe[SequenceManagerState]()
    eventually {
      smRef ! GetSequenceManagerState(stateProbe.ref)
      stateProbe.expectMessage(state)
    }
  }

  private def akkaLocation(componentId: ComponentId) = AkkaLocation(AkkaConnection(componentId), URI.create("uri"))
}
