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
import esw.sm.api.SequenceManagerState
import esw.sm.api.SequenceManagerState._
import esw.sm.api.actor.messages.SequenceManagerMsg
import esw.sm.api.actor.messages.SequenceManagerMsg._
import esw.sm.api.protocol.CommonFailure.{ConfigurationMissing, LocationServiceError}
import esw.sm.api.protocol.ConfigureResponse.{ConflictingResourcesWithRunningObsMode, Success}
import esw.sm.api.protocol.ShutdownSequenceComponentsPolicy.{AllSequenceComponents, SingleSequenceComponent}
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
  private val locationServiceUtil: LocationServiceUtil     = mock[LocationServiceUtil]
  private val agentUtil: AgentUtil                         = mock[AgentUtil]
  private val sequencerUtil: SequencerUtil                 = mock[SequencerUtil]
  private val sequenceComponentUtil: SequenceComponentUtil = mock[SequenceComponentUtil]
  private val sequenceManagerBehavior = new SequenceManagerBehavior(
    config,
    locationServiceUtil,
    agentUtil,
    sequencerUtil,
    sequenceComponentUtil
  )

  private lazy val smRef: ActorRef[SequenceManagerMsg] = actorSystem.systemActorOf(sequenceManagerBehavior.setup, "test_actor")

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds)

  override protected def beforeEach(): Unit = reset(locationServiceUtil, sequencerUtil, sequenceComponentUtil, agentUtil)

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

      when(sequenceComponentUtil.loadScript(ESW, darkNight)).thenReturn(future(1.second, Right(Started(componentId))))
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
      when(sequenceComponentUtil.loadScript(ESW, darkNight)).thenReturn(futureLeft(expectedErrorResponse))

      val startSequencerResponseProbe = TestProbe[StartSequencerResponse]()

      smRef ! StartSequencer(ESW, darkNight, startSequencerResponseProbe.ref)

      startSequencerResponseProbe.expectMessage(expectedErrorResponse)
      verify(sequenceComponentUtil).loadScript(ESW, darkNight)
      verify(locationServiceUtil).find(httpConnection)
    }
  }

  "ShutdownSequencers" must {
    val errorMsg = "error"

    val singlePolicy    = ShutdownSequencersPolicy.SingleSequencer(ESW, darkNight)
    val obsModePolicy   = ShutdownSequencersPolicy.ObsModeSequencers(darkNight)
    val subsystemPolicy = ShutdownSequencersPolicy.SubsystemSequencers(TCS)
    val allPolicy       = ShutdownSequencersPolicy.AllSequencers

    val policies = Table(
      ("policy", "locationServiceFailure"),
      (singlePolicy, LocationServiceError(errorMsg)),
      (obsModePolicy, LocationServiceError(errorMsg)),
      (subsystemPolicy, LocationServiceError(errorMsg)),
      (allPolicy, LocationServiceError(errorMsg))
    )

    forAll(policies) { (policy, locationServiceFailure) =>
      val policyName = policy.getClass.getSimpleName
      s"transition sm from Idle -> Processing -> Idle state and stop $policyName | ESW-326, ESW-345, ESW-166, ESW-324, ESW-342" in {
        when(sequencerUtil.shutdownSequencers(policy)).thenReturn(future(1.seconds, ShutdownSequencersResponse.Success))
        val responseProbe = TestProbe[ShutdownSequencersResponse]()

        // STATE TRANSITION: Idle -> ShutdownSequencers -> Processing -> Idle
        assertState(Idle)
        smRef ! ShutdownSequencers(policy, responseProbe.ref)
        assertState(Processing)
        assertState(Idle)

        responseProbe.expectMessage(ShutdownSequencersResponse.Success)
        verify(sequencerUtil).shutdownSequencers(policy)
      }

      s"return LocationServiceError if location service fails for $policyName | ESW-326, ESW-345, ESW-166, ESW-324" in {
        when(sequencerUtil.shutdownSequencers(policy)).thenReturn(Future.successful(locationServiceFailure))
        val shutdownSequencerResponseProbe = TestProbe[ShutdownSequencersResponse]()

        smRef ! ShutdownSequencers(policy, shutdownSequencerResponseProbe.ref)
        shutdownSequencerResponseProbe.expectMessage(locationServiceFailure)

        verify(sequencerUtil).shutdownSequencers(policy)
      }
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
    "transition sm from Idle -> Processing -> Idle state and return success on shutdown | ESW-338, ESW-342" in {
      val prefix = Prefix(ESW, "primary")

      val singleShutdownPolicy = SingleSequenceComponent(prefix)
      when(sequenceComponentUtil.shutdown(singleShutdownPolicy))
        .thenReturn(future(1.second, ShutdownSequenceComponentResponse.Success))

      val shutdownSequenceComponentResponseProbe = TestProbe[ShutdownSequenceComponentResponse]()

      // STATE TRANSITION: Idle -> ShutdownSequenceComponents -> Processing -> Idle
      assertState(Idle)
      smRef ! ShutdownSequenceComponents(SingleSequenceComponent(prefix), shutdownSequenceComponentResponseProbe.ref)
      assertState(Processing)
      assertState(Idle)

      shutdownSequenceComponentResponseProbe.expectMessage(ShutdownSequenceComponentResponse.Success)
      verify(sequenceComponentUtil).shutdown(singleShutdownPolicy)
    }

    "return Success when shutting down all sequence components | ESW-346" in {
      when(sequenceComponentUtil.shutdown(AllSequenceComponents))
        .thenReturn(Future.successful(ShutdownSequenceComponentResponse.Success))

      val shutdownSequenceComponentResponseProbe = TestProbe[ShutdownSequenceComponentResponse]()

      smRef ! ShutdownSequenceComponents(AllSequenceComponents, shutdownSequenceComponentResponseProbe.ref)
      shutdownSequenceComponentResponseProbe.expectMessage(ShutdownSequenceComponentResponse.Success)

      verify(sequenceComponentUtil).shutdown(AllSequenceComponents)
    }

    "return LocationServiceError if LocationServiceError encountered while shutting down sequence components | ESW-338,ESW-346" in {
      val prefix = Prefix(ESW, "primary")

      val error          = LocationServiceError("location service error")
      val shutdownPolicy = SingleSequenceComponent(prefix)

      when(sequenceComponentUtil.shutdown(shutdownPolicy)).thenReturn(Future.successful(error))
      val shutdownSequenceComponentResponseProbe = TestProbe[ShutdownSequenceComponentResponse]()

      smRef ! ShutdownSequenceComponents(shutdownPolicy, shutdownSequenceComponentResponseProbe.ref)
      shutdownSequenceComponentResponseProbe.expectMessage(error)

      verify(sequenceComponentUtil).shutdown(shutdownPolicy)
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

  "GetAgentStatus" must {
    "return agent status successfully | ESW-349" in {
      val eswMachine1 = ComponentId(Prefix(ESW, "machine1"), Machine)
      val eswMachine2 = ComponentId(Prefix(ESW, "machine2"), Machine)
      val tcsMachine1 = ComponentId(Prefix(TCS, "machine1"), Machine)

      val eswSeqComp1 = ComponentId(Prefix(ESW, "primary"), SequenceComponent)
      val eswSeqComp2 = ComponentId(Prefix(ESW, "secondary"), SequenceComponent)
      val tcsSeqComp1 = ComponentId(Prefix(TCS, "primary"), SequenceComponent)

      val agentLocations = List(akkaLocation(eswMachine1), akkaLocation(eswMachine2), akkaLocation(tcsMachine1))

      val agentToSeqCompMap = Map(
        eswMachine1 -> List(eswSeqComp1, eswSeqComp2),
        eswMachine2 -> List(),
        tcsMachine1 -> List(tcsSeqComp1)
      )

      val eswMachine1SeqComps =
        Map(eswSeqComp1 -> Some(akkaLocation(ComponentId(Prefix(ESW, "darkNight"), Sequencer))), eswSeqComp2 -> None)
      val tcsMachine1SeqComps = Map(tcsSeqComp1 -> Some(akkaLocation(ComponentId(Prefix(TCS, "darkNight"), Sequencer))))

      when(locationServiceUtil.listAkkaLocationsBy(Machine)).thenReturn(futureRight(agentLocations))
      when(agentUtil.getSequenceComponentsRunningOn(agentLocations)).thenReturn(Future.successful(agentToSeqCompMap))
      when(sequenceComponentUtil.getSequenceComponentStatus(List(eswSeqComp1, eswSeqComp2)))
        .thenReturn(Future.successful(eswMachine1SeqComps))
      when(sequenceComponentUtil.getSequenceComponentStatus(List.empty)).thenReturn(Future.successful(Map.empty))
      when(sequenceComponentUtil.getSequenceComponentStatus(List(tcsSeqComp1))).thenReturn(
        Future.successful(tcsMachine1SeqComps)
      )

      val expectedResponse = GetAgentStatusResponse.Success(
        Map(eswMachine1 -> eswMachine1SeqComps, eswMachine2 -> Map.empty, tcsMachine1 -> tcsMachine1SeqComps)
      )
      val getAgentStatusResponseProbe = TestProbe[GetAgentStatusResponse]()

      assertState(Idle)
      smRef ! GetAgentStatus(getAgentStatusResponseProbe.ref)
      assertState(Idle)

      getAgentStatusResponseProbe.expectMessage(expectedResponse)

      verify(locationServiceUtil).listAkkaLocationsBy(Machine)
      verify(agentUtil).getSequenceComponentsRunningOn(agentLocations)
      verify(sequenceComponentUtil).getSequenceComponentStatus(List(eswSeqComp1, eswSeqComp2))
      verify(sequenceComponentUtil).getSequenceComponentStatus(List.empty)
      verify(sequenceComponentUtil).getSequenceComponentStatus(List(tcsSeqComp1))
    }

    "return LocationServiceError if location service gives error | ESW-349" in {
      when(locationServiceUtil.listAkkaLocationsBy(Machine)).thenReturn(futureLeft(RegistrationListingFailed("error")))

      val getAgentStatusResponseProbe = TestProbe[GetAgentStatusResponse]()

      smRef ! GetAgentStatus(getAgentStatusResponseProbe.ref)

      getAgentStatusResponseProbe.expectMessage(LocationServiceError("error"))

      verify(locationServiceUtil).listAkkaLocationsBy(Machine)
    }
  }

  private def assertState(state: SequenceManagerState) = {
    val stateProbe = TestProbe[SequenceManagerState]()
    eventually {
      smRef ! GetSequenceManagerState(stateProbe.ref)
      stateProbe.expectMessage(state)
    }
  }

  private def akkaLocation(componentId: ComponentId) =
    AkkaLocation(AkkaConnection(componentId), URI.create("uri"))
}
