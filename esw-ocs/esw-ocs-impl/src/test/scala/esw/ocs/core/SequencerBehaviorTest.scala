package esw.ocs.core

import akka.Done
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import csw.command.client.messages.{ProcessSequence, SequencerMsg}
import csw.params.commands.CommandResponse.{Completed, Error, SubmitResponse}
import csw.params.commands.{CommandName, ProcessSequenceError, Sequence, Setup}
import csw.params.core.models.{Id, Prefix}
import esw.ocs.BaseTestSuite
import esw.ocs.api.EditorResponse
import esw.ocs.api.models.messages.SequencerMessages._
import esw.ocs.api.models.messages.error.EditorError
import esw.ocs.api.models.{Step, StepList}
import esw.ocs.dsl.ScriptDsl
import org.mockito.Mockito.{verify, when}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

class SequencerBehaviorTest extends ScalaTestWithActorTestKit with BaseTestSuite with MockitoSugar {
  private val sequencer = mock[Sequencer]
  private val scriptDsl = mock[ScriptDsl]

  private val sequencerActor = spawn(SequencerBehavior.behavior(sequencer, scriptDsl))

  "ProcessSequence" in {
    val command1 = Setup(Prefix("test"), CommandName("command-1"), None)
    val sequence = Sequence(Id(), Seq(command1))

    runTest[Either[ProcessSequenceError, SubmitResponse]](
      mockFunction = sequencer.processSequence(sequence),
      mockResponse = Right(Completed(command1.runId)),
      testMsg = ProcessSequence(sequence, _)
    )
  }

  "Available" in {
    runTest[Boolean](
      mockFunction = sequencer.isAvailable,
      mockResponse = true,
      testMsg = Available
    )
  }

  "GetSequence" in {
    runTest[StepList](
      mockFunction = sequencer.getSequence,
      mockResponse = StepList.empty,
      testMsg = GetSequence
    )
  }

  "GetPreviousSequence" in {
    runTest[Option[StepList]](
      mockFunction = sequencer.getPreviousSequence,
      mockResponse = Some(StepList.empty),
      testMsg = GetPreviousSequence
    )
  }

  "Add" in {
    val command1 = Setup(Prefix("test"), CommandName("command-1"), None)

    runTestForEditorResponse(
      mockFunction = sequencer.add(List(command1)),
      mockResponse = Right(Done),
      testMsg = Add(List(command1), _)
    )
  }

  "Pause" in {
    runTestForEditorResponse(
      mockFunction = sequencer.pause,
      mockResponse = Right(Done),
      testMsg = Pause
    )
  }

  "Resume" in {
    runTestForEditorResponse(
      mockFunction = sequencer.resume,
      mockResponse = Right(Done),
      testMsg = Resume
    )
  }

  "Reset" in {
    runTestForEditorResponse(
      mockFunction = sequencer.reset(),
      mockResponse = Right(Done),
      testMsg = Reset
    )
  }

  "Replace" in {
    val runId    = Id()
    val command1 = Setup(Prefix("test"), CommandName("command-1"), None)

    runTestForEditorResponse(
      mockFunction = sequencer.replace(runId, List(command1)),
      mockResponse = Right(Done),
      testMsg = Replace(runId, List(command1), _)
    )
  }

  "Prepend" in {
    val command1 = Setup(Prefix("test"), CommandName("command-1"), None)

    runTestForEditorResponse(
      mockFunction = sequencer.prepend(List(command1)),
      mockResponse = Right(Done),
      testMsg = Prepend(List(command1), _)
    )

  }

  "Delete" in {
    val runId = Id()

    runTestForEditorResponse(
      mockFunction = sequencer.delete(runId),
      mockResponse = Right(Done),
      testMsg = Delete(runId, _)
    )
  }

  "InsertAfter" in {
    val runId    = Id()
    val command1 = Setup(Prefix("test"), CommandName("command-1"), None)

    runTestForEditorResponse(
      mockFunction = sequencer.insertAfter(runId, List(command1)),
      mockResponse = Right(Done),
      testMsg = InsertAfter(runId, List(command1), _)
    )
  }

  "AddBreakpoint" in {
    val runId = Id()

    runTestForEditorResponse(
      mockFunction = sequencer.addBreakpoint(runId),
      mockResponse = Right(Done),
      testMsg = AddBreakpoint(runId, _)
    )
  }

  "RemoveBreakpoint" in {
    val runId = Id()

    runTestForEditorResponse(
      mockFunction = sequencer.removeBreakpoint(runId),
      mockResponse = Right(Done),
      testMsg = RemoveBreakpoint(runId, _)
    )
  }

  "PullNext" in {
    val command1 = Setup(Prefix("test"), CommandName("command-1"), None)

    runTest[Step](
      mockFunction = sequencer.pullNext(),
      mockResponse = Step(command1),
      testMsg = PullNext
    )
  }

  "MaybeNext" in {
    val command1 = Setup(Prefix("test"), CommandName("command-1"), None)

    runTest[Option[Step]](
      mockFunction = sequencer.mayBeNext,
      mockResponse = Some(Step(command1)),
      testMsg = MaybeNext
    )
  }

  "ReadyToExecuteNext" in {
    runTest[Done](
      mockFunction = sequencer.readyToExecuteNext(),
      mockResponse = Done,
      testMsg = ReadyToExecuteNext
    )
  }

  "UpdateFailure" in {
    val errorResponse: SubmitResponse = Error(Id(), "")

    sequencerActor ! UpdateFailure(errorResponse)

    verify(sequencer).updateFailure(errorResponse)
  }

  private def runTest[R](mockFunction: => Future[R], mockResponse: R, testMsg: ActorRef[R] => SequencerMsg): Unit = {
    val testProbe: TestProbe[R] = TestProbe()

    when(mockFunction).thenReturn(Future.successful(mockResponse))

    sequencerActor ! testMsg(testProbe.ref)
    testProbe.expectMessage(mockResponse)
  }

  private def runTestForEditorResponse(
      mockFunction: => Future[Either[EditorError, Done]],
      mockResponse: Either[EditorError, Done],
      testMsg: ActorRef[EditorResponse] => SequencerMsg
  ): EditorResponse = {
    val testProbe: TestProbe[EditorResponse] = TestProbe()

    when(mockFunction).thenReturn(Future.successful(mockResponse))

    sequencerActor ! testMsg(testProbe.ref)
    testProbe.expectMessage(EditorResponse(mockResponse))
  }
}
