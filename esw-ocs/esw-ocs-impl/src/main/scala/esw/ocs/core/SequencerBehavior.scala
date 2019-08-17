package esw.ocs.core

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout
import csw.command.client.CommandResponseManager
import csw.command.client.messages.sequencer.SequencerMsg
import csw.location.api.scaladsl.LocationService
import csw.location.models.ComponentId
import csw.location.models.Connection.AkkaConnection
import csw.params.commands.Sequence
import esw.ocs.api.codecs.OcsCodecs
import esw.ocs.api.models.messages.SequencerMessages._
import esw.ocs.api.models.messages.{GoOnlineHookFailed, _}
import esw.ocs.api.models.{SequencerState, StepList}
import esw.ocs.dsl.ScriptDsl

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class SequencerBehavior(
    componentId: ComponentId,
    script: ScriptDsl,
    locationService: LocationService,
    crm: CommandResponseManager
)(implicit val actorSystem: ActorSystem[_], timeout: Timeout)
    extends CustomReceive
    with OcsCodecs {

  def setup: Behavior[SequencerMsg] = Behaviors.setup { ctx =>
    idle(SequencerState.initial(ctx.self, crm))
  }

  //BEHAVIORS
  private def idle(state: SequencerState): Behavior[SequencerMsg] = receive[IdleMessage]("idle") { (ctx, msg) =>
    import ctx.executionContext
    msg match {
      case msg: CommonMessage                              => handleCommonMessage(msg, state, _ => ctx.system.terminate)
      case LoadSequence(sequence, replyTo)                 => load(sequence, replyTo, state)(nextBehavior = loaded)
      case LoadAndStartSequenceInternal(sequence, replyTo) => loadAndStart(sequence, state, replyTo)
      case GoOffline(replyTo)                              => goOffline(replyTo, state)
      case PullNext(replyTo)                               => idle(state.pullNextStep(replyTo))
    }
  }

  private def loaded(state: SequencerState): Behavior[SequencerMsg] = receive[SequenceLoadedMessage]("loaded") { (ctx, msg) =>
    import ctx.executionContext
    msg match {
      case msg: CommonMessage         => handleCommonMessage(msg, state, _ => ctx.system.terminate)
      case editorAction: EditorAction => loaded(handleEditorAction(editorAction, state))
      case GoOffline(replyTo)         => goOffline(replyTo, state)
      case StartSequence(replyTo)     => start(state, replyTo)
    }
  }

  private def inProgress(state: SequencerState): Behavior[SequencerMsg] = receive[InProgressMessage]("in-progress") {
    (ctx, msg) =>
      import ctx.executionContext
      msg match {
        case msg: CommonMessage          => handleCommonMessage(msg, state, _ => ctx.system.terminate)
        case msg: EditorAction           => inProgress(handleEditorAction(msg, state))
        case PullNext(replyTo)           => inProgress(state.pullNextStep(replyTo))
        case MaybeNext(replyTo)          => replyTo ! MaybeNextResult(state.stepList.flatMap(_.nextExecutable)); Behaviors.same
        case ReadyToExecuteNext(replyTo) => inProgress(state.readyToExecuteNext(replyTo))
        case Update(submitResponse, _)   => inProgress(state.updateStepStatus(submitResponse))
        case _: GoIdle                   => idle(state)
      }
  }

  private def offline(state: SequencerState): Behavior[SequencerMsg] = receive[OfflineMessage]("offline") { (ctx, message) =>
    import ctx.executionContext
    message match {
      case msg: CommonMessage => handleCommonMessage(msg, state, _ => ctx.system.terminate)
      case GoOnline(replyTo)  => goOnline(replyTo, state)(fallbackBehavior = offline, nextBehavior = idle)
    }
  }

  private def handleCommonMessage(
      message: CommonMessage,
      state: SequencerState,
      killFunction: Unit => Unit
  )(implicit ec: ExecutionContext): Behavior[SequencerMsg] = message match {
    case Shutdown(replyTo)            => shutdown(state, replyTo, killFunction)
    case GetSequence(replyTo)         => sendStepListResponse(replyTo, state.stepList)
    case GetPreviousSequence(replyTo) => sendStepListResponse(replyTo, state.previousStepList)
  }

  private def handleEditorAction(editorAction: EditorAction, state: SequencerState): SequencerState = {
    import state._
    editorAction match {
      case Abort(replyTo)                     => ??? //story not played yet
      case Add(commands, replyTo)             => updateStepList(replyTo, stepList.map(_.append(commands)))
      case Pause(replyTo)                     => updateStepListResult(replyTo, stepList.map(_.pause))
      case Resume(replyTo)                    => updateStepList(replyTo, stepList.map(_.resume))
      case Reset(replyTo)                     => updateStepList(replyTo, stepList.map(_.discardPending))
      case Replace(id, commands, replyTo)     => updateStepListResult(replyTo, stepList.map(_.replace(id, commands)))
      case Prepend(commands, replyTo)         => updateStepList(replyTo, stepList.map(_.prepend(commands)))
      case Delete(id, replyTo)                => updateStepListResult(replyTo, stepList.map(_.delete(id)))
      case InsertAfter(id, commands, replyTo) => updateStepListResult(replyTo, stepList.map(_.insertAfter(id, commands)))
      case AddBreakpoint(id, replyTo)         => updateStepListResult(replyTo, stepList.map(_.addBreakpoint(id)))
      case RemoveBreakpoint(id, replyTo)      => updateStepListResult(replyTo, stepList.map(_.removeBreakpoint(id)))
    }
  }

  private def load(sequence: Sequence, replyTo: ActorRef[LoadSequenceResponse], state: SequencerState)(
      nextBehavior: SequencerState => Behavior[SequencerMsg]
  ): Behavior[SequencerMsg] =
    createStepList(sequence, state) match {
      case Left(err)       => replyTo ! err; Behaviors.same
      case Right(newState) => replyTo ! Ok; nextBehavior(newState)
    }

  private def start(state: SequencerState, replyTo: ActorRef[SequenceResponse]): Behavior[SequencerMsg] =
    inProgress(state.startSequence(replyTo))

  private def loadAndStart(
      sequence: Sequence,
      state: SequencerState,
      replyTo: ActorRef[SequenceResponse]
  ): Behavior[SequencerMsg] =
    createStepList(sequence, state) match {
      case Left(err)       => replyTo ! err; Behaviors.same
      case Right(newState) => start(newState, replyTo)
    }

  private def createStepList(sequence: Sequence, state: SequencerState): Either[DuplicateIdsFound.type, SequencerState] =
    StepList(sequence).map(currentStepList => state.copy(stepList = Some(currentStepList), previousStepList = state.stepList))

  private def sendStepListResponse(replyTo: ActorRef[StepListResponse], stepList: Option[StepList]): Behavior[SequencerMsg] = {
    replyTo ! StepListResult(stepList)
    Behaviors.same
  }

  private def shutdown(state: SequencerState, replyTo: ActorRef[OkOrUnhandledResponse], killFunction: Unit => Unit)(
      implicit ec: ExecutionContext
  ): Behavior[SequencerMsg] = {

    // run both the futures in parallel and wait for both to complete
    // once all finished, send ShutdownComplete self message irrespective of any failures
    val f1 = locationService.unregister(AkkaConnection(componentId))
    val f2 = script.executeShutdown()
    f1.onComplete(_ => f2.onComplete(_ => state.self ! ShutdownComplete(replyTo)))

    shuttingDown(killFunction)
  }

  private def shuttingDown(killFunction: Unit => Unit) = receive[ShuttingDownMessage]("shutting-down") {
    case (_, ShutdownComplete(replyTo)) =>
      replyTo ! Ok
      killFunction(())
      Behaviors.stopped
  }

  private def goingOnline(state: SequencerState)(
      fallbackBehavior: SequencerState => Behavior[SequencerMsg],
      nextBehavior: SequencerState => Behavior[SequencerMsg]
  ): Behavior[SequencerMsg] =
    receive[GoingOnlineMessage]("going-online") { (ctx, message) =>
      import ctx.executionContext
      message match {
        case msg: CommonMessage       => handleCommonMessage(msg, state, _ => ctx.system.terminate)
        case GoOnlineSuccess(replyTo) => replyTo ! Ok; nextBehavior(state)
        case GoOnlineFailed(replyTo)  => replyTo ! GoOnlineHookFailed; fallbackBehavior(state)
      }
    }

  private def goOnline(replyTo: ActorRef[GoOnlineResponse], state: SequencerState)(
      fallbackBehavior: SequencerState => Behavior[SequencerMsg],
      nextBehavior: SequencerState => Behavior[SequencerMsg]
  )(implicit ec: ExecutionContext): Behavior[SequencerMsg] = {
    script.executeGoOnline().onComplete {
      case Success(_) => state.self ! GoOnlineSuccess(replyTo)
      case Failure(_) => state.self ! GoOnlineFailed(replyTo)
    }
    goingOnline(state)(fallbackBehavior, nextBehavior)
  }

  private def goingOffline(state: SequencerState): Behavior[SequencerMsg] = receive[GoingOfflineMessage]("going-offline") {
    (ctx, message) =>
      import ctx.executionContext
      message match {
        case msg: CommonMessage   => handleCommonMessage(msg, state, _ => ctx.system.terminate)
        case GoneOffline(replyTo) => replyTo ! Ok; offline(state.copy(stepList = None))
      }
  }

  private def goOffline(replyTo: ActorRef[OkOrUnhandledResponse], state: SequencerState)(
      implicit ec: ExecutionContext
  ): Behavior[SequencerMsg] = {
    // go to offline state even if handler fails, note that this is different than GoOnline
    script.executeGoOffline().onComplete(_ => state.self ! GoneOffline(replyTo))
    goingOffline(state)
  }
}
