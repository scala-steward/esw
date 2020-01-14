package esw.ocs.dsl.script

import java.util.concurrent.CompletableFuture

import akka.Done
import csw.params.commands.SequenceCommand
import csw.time.core.models.UTCTime
import esw.ocs.api.BaseTestSuite
import esw.ocs.dsl.params.Params
import esw.ocs.impl.core.SequenceOperator

import scala.concurrent.Future

class FsmScriptDslTest extends BaseTestSuite {
  private val strandEc           = StrandEc()
  private val seqOperatorFactory = () => mock[SequenceOperator]
  private val STARTED_STATE      = "STARTED"
  private val params             = Params()

  override protected def afterAll(): Unit = strandEc.shutdown()

  "become" must {
    "call transition method defined on FsmScriptState and update its internal state | ESW-252" in {
      val initialState = mock[FsmScriptState]
      val updatedState = mock[FsmScriptState]
      when(initialState.transition(STARTED_STATE, params)).thenReturn(updatedState)

      val scriptDsl = new FsmScriptDsl(seqOperatorFactory, strandEc, initialState)
      scriptDsl.become(STARTED_STATE, params)

      verify(initialState).transition(STARTED_STATE, params)
      scriptDsl.getState should ===(updatedState)
    }
  }

  "add" must {
    "call add method defined on FsmScriptState and update its internal state" in {
      val initialState = mock[FsmScriptState]
      val updatedState = mock[FsmScriptState]
      val handler      = (_: Params) => mock[ScriptDsl]
      when(initialState.add(STARTED_STATE, handler)).thenReturn(updatedState)

      val scriptDsl = new FsmScriptDsl(seqOperatorFactory, strandEc, initialState)
      scriptDsl.add(STARTED_STATE, handler)

      verify(initialState).add(STARTED_STATE, handler)
      scriptDsl.getState should ===(updatedState)
    }
  }

  "execute" must {
    "delegate call to appropriate execute method defined on current script present in script state" in {
      val state           = mock[FsmScriptState]
      val script          = mock[ScriptDsl]
      val sequenceCommand = mock[SequenceCommand]
      val futureUnit      = mock[Future[Unit]]
      val futureDone      = Future.successful(Done)
      val utcTime         = UTCTime.now()
      val hint            = "datum"
      val ex              = mock[Throwable]

      val completionStageVoid: CompletableFuture[Void] = CompletableFuture.completedFuture(null)

      when(state.currentScript).thenReturn(script)
      when(script.execute(sequenceCommand)).thenReturn(futureUnit)
      when(script.executeGoOnline()).thenReturn(futureDone)
      when(script.executeGoOnline()).thenReturn(futureDone)
      when(script.executeGoOffline()).thenReturn(futureDone)
      when(script.executeShutdown()).thenReturn(futureDone)
      when(script.executeAbort()).thenReturn(futureDone)
      when(script.executeStop()).thenReturn(futureDone)
      when(script.executeDiagnosticMode(utcTime, hint)).thenReturn(futureDone)
      when(script.executeOperationsMode()).thenReturn(futureDone)
      when(script.executeExceptionHandlers(ex)).thenReturn(completionStageVoid)

      val scriptDsl = new FsmScriptDsl(seqOperatorFactory, strandEc, state)

      scriptDsl.execute(sequenceCommand) should ===(futureUnit)
      verify(script).execute(sequenceCommand)

      scriptDsl.executeGoOnline().futureValue should ===(Done)
      verify(script).executeGoOnline()

      scriptDsl.executeGoOffline().futureValue should ===(Done)
      verify(script).executeGoOffline()

      scriptDsl.executeShutdown().futureValue should ===(Done)
      verify(script).executeShutdown()

      scriptDsl.executeAbort().futureValue should ===(Done)
      verify(script).executeAbort()

      scriptDsl.executeStop().futureValue should ===(Done)
      verify(script).executeStop()

      scriptDsl.executeDiagnosticMode(utcTime, hint).futureValue should ===(Done)
      verify(script).executeDiagnosticMode(utcTime, hint)

      scriptDsl.executeOperationsMode().futureValue should ===(Done)
      verify(script).executeOperationsMode()

      scriptDsl.executeExceptionHandlers(ex)
      verify(script).executeExceptionHandlers(ex)
    }
  }
}
