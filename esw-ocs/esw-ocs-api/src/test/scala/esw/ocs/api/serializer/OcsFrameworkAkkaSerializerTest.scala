package esw.ocs.api.serializer

import java.net.URI

import akka.Done
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ActorRef, ActorSystem, SpawnProtocol}
import akka.serialization.{SerializationExtension, Serializer}
import csw.command.client.messages.sequencer.SequenceResponse
import csw.location.models.Connection.AkkaConnection
import csw.location.models.{AkkaLocation, ComponentId, ComponentType}
import csw.params.commands._
import csw.params.core.models.{Id, Prefix}
import esw.ocs.api.BaseTestSuite
import esw.ocs.api.models.StepStatus.{Finished, InFlight}
import esw.ocs.api.models.messages.SequenceComponentMsg.{GetStatus, LoadScript, UnloadScript}
import esw.ocs.api.models.messages.SequenceComponentResponse.{GetStatusResponse, LoadScriptResponse}
import esw.ocs.api.models.messages.SequencerMessages._
import esw.ocs.api.models.messages.error.StepListError._
import esw.ocs.api.models.messages.error._
import esw.ocs.api.models.messages.{EditorResponse, LifecycleResponse, LoadSequenceResponse, StepListResponse}
import esw.ocs.api.models.{Step, StepList, StepStatus}
import org.scalatest.prop.TableDrivenPropertyChecks.forAll
import org.scalatest.prop.Tables.Table

import scala.concurrent.Await
import scala.concurrent.duration.DurationDouble

class OcsFrameworkAkkaSerializerTest extends BaseTestSuite {
  private final implicit val system: ActorSystem[SpawnProtocol] = ActorSystem(SpawnProtocol.behavior, "test")
  private final val serialization                               = SerializationExtension(system.toUntyped)

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, 2.seconds)
  }

  val loadSequenceResponseProbeRef: ActorRef[LoadSequenceResponse] = TestProbe[LoadSequenceResponse].ref
  val startSequenceResponseProbeRef: ActorRef[SequenceResponse]    = TestProbe[SequenceResponse].ref
  val lifecycleResponseProbeRef: ActorRef[LifecycleResponse]       = TestProbe[LifecycleResponse].ref
  val editorResponseProbeRef: ActorRef[EditorResponse]             = TestProbe[EditorResponse].ref
  val stepListResponseProbeRef: ActorRef[StepList]                 = TestProbe[StepList].ref
  val stepListOptionResponseProbeRef: ActorRef[StepListResponse]   = TestProbe[StepListResponse].ref
  val booleanResponseProbeRef: ActorRef[Boolean]                   = TestProbe[Boolean].ref
  val setupCommand                                                 = Setup(Prefix("test"), CommandName("test"), None)
  val steps: List[Step]                                            = List(Step(setupCommand))
  val sequenceCommandList: List[SequenceCommand]                   = List(setupCommand)
  val loadScriptResponseProbeRef: ActorRef[LoadScriptResponse] =
    TestProbe[LoadScriptResponse].ref
  val getStatusResponseProbeRef: ActorRef[GetStatusResponse] =
    TestProbe[GetStatusResponse].ref
  val unloadScriptResponseProbeRef: ActorRef[Done] =
    TestProbe[Done].ref
  val akkaLocation = AkkaLocation(AkkaConnection(ComponentId("test", ComponentType.Sequencer)), Prefix("test"), new URI("uri"))

  "Load and Start Msg" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val testData = Table(
        "Load and Start sequence Msg",
        LoadSequence(Sequence(setupCommand), loadSequenceResponseProbeRef),
        StartSequence(startSequenceResponseProbeRef)
      )
      forAll(testData) { seqMsg =>
        val serializer = serialization.findSerializerFor(seqMsg)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(seqMsg)
        serializer.fromBinary(bytes, Some(seqMsg.getClass)) shouldEqual seqMsg
      }
    }
  }

  "ExternalEditorSequencerMsg" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val testData = Table(
        "ExternalEditorSequencerMsg models",
        Available(booleanResponseProbeRef.ref),
        GetSequence(stepListResponseProbeRef),
        GetPreviousSequence(stepListOptionResponseProbeRef),
        Add(sequenceCommandList, editorResponseProbeRef),
        Prepend(sequenceCommandList, editorResponseProbeRef),
        Replace(Id(), sequenceCommandList, editorResponseProbeRef),
        InsertAfter(Id(), sequenceCommandList, editorResponseProbeRef),
        Delete(Id(), editorResponseProbeRef),
        AddBreakpoint(Id(), editorResponseProbeRef),
        RemoveBreakpoint(Id(), editorResponseProbeRef),
        Pause(editorResponseProbeRef),
        Resume(editorResponseProbeRef),
        Reset(editorResponseProbeRef)
      )

      forAll(testData) { externalSequencerMsg =>
        val serializer = serialization.findSerializerFor(externalSequencerMsg)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(externalSequencerMsg)
        serializer.fromBinary(bytes, Some(externalSequencerMsg.getClass)) shouldEqual externalSequencerMsg
      }
    }
  }

  "LifecycleMsg" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val testData = Table(
        "LifecycleMsg models",
        GoOnline(lifecycleResponseProbeRef.ref),
        GoOffline(lifecycleResponseProbeRef.ref),
        Shutdown(lifecycleResponseProbeRef.ref),
        Abort(lifecycleResponseProbeRef.ref)
      )

      forAll(testData) { externalSequencerMsg =>
        val serializer = serialization.findSerializerFor(externalSequencerMsg)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(externalSequencerMsg)
        serializer.fromBinary(bytes, Some(externalSequencerMsg.getClass)) shouldEqual externalSequencerMsg
      }
    }
  }

  "StepList" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val testData = Table(
        "StepList model",
        StepList(Id(), steps)
      )

      forAll(testData) { stepList =>
        val serializer = serialization.findSerializerFor(stepList)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(stepList)
        serializer.fromBinary(bytes, Some(stepList.getClass)) shouldEqual stepList
      }
    }
  }

  "SequenceComponentMsg" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val testData = Table(
        "SequenceComponentMsg models",
        LoadScript("sequencerId", "observingMode", loadScriptResponseProbeRef),
        GetStatus(getStatusResponseProbeRef),
        UnloadScript(unloadScriptResponseProbeRef)
      )

      forAll(testData) { sequenceComponentMsg =>
        val serializer = serialization.findSerializerFor(sequenceComponentMsg)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(sequenceComponentMsg)
        serializer.fromBinary(bytes, Some(sequenceComponentMsg.getClass)) shouldEqual sequenceComponentMsg
      }
    }
  }

  "SequenceComponentResponse" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val akkaLocation = AkkaLocation(
        AkkaConnection(ComponentId("testComponent", ComponentType.Sequencer)),
        Prefix("test.component"),
        new URI("testURI")
      )
      val testData = Table(
        "SequenceComponentResponse models",
        LoadScriptResponse(Left(RegistrationError("error"))),
        LoadScriptResponse(
          Right(
            akkaLocation
          )
        ),
        GetStatusResponse(Some(akkaLocation)),
        GetStatusResponse(None)
      )

      forAll(testData) { model =>
        val serializer: Serializer = serialization.findSerializerFor(model)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(model)
        serializer.fromBinary(bytes, Some(model.getClass)) shouldEqual model
      }
    }
  }

  "EditorResponse" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val testData = Table(
        "EditorResponse",
        EditorResponse(Left(NotSupported(StepStatus.InFlight))),
        EditorResponse(Left(AddFailed)),
        EditorResponse(Left(UpdateNotSupported(Finished.Success(CommandResponse.Completed(Id())), InFlight))),
        EditorResponse(Left(PauseFailed)),
        EditorResponse(Left(IdDoesNotExist(Id())))
      )

      forAll(testData) { model =>
        val serializer: Serializer = serialization.findSerializerFor(model)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(model)
        serializer.fromBinary(bytes, Some(model.getClass)) shouldEqual model
      }
    }
  }

  "LifecycleResponse" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val testData = Table(
        "LifecycleResponse",
        LifecycleResponse(Left(GoOnlineError("GoOnlineError"))),
        LifecycleResponse(Left(GoOfflineError("GoOfflineError"))),
        LifecycleResponse(Left(ShutdownError("ShutdownError"))),
        LifecycleResponse(Left(AbortError("AbortError")))
      )

      forAll(testData) { model =>
        val serializer: Serializer = serialization.findSerializerFor(model)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(model)
        serializer.fromBinary(bytes, Some(model.getClass)) shouldEqual model
      }
    }
  }

  "StepListResponse" must {
    "use OcsFrameworkAkkaSerializer for (de)serialization" in {
      val testData = Table(
        "StepListResponse",
        StepListResponse(Some(StepList(Id(), List(Step(Setup(Prefix("test"), CommandName("test"), None))))))
      )

      forAll(testData) { model =>
        val serializer: Serializer = serialization.findSerializerFor(model)
        serializer.getClass shouldBe classOf[OcsFrameworkAkkaSerializer]

        val bytes = serializer.toBinary(model)
        serializer.fromBinary(bytes, Some(model.getClass)) shouldEqual model
      }
    }
  }

}
