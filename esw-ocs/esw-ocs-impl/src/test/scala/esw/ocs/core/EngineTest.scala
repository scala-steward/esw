package esw.ocs.core

import akka.Done
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import csw.params.commands.CommandResponse.Error
import csw.params.commands.SequenceCommand
import csw.params.core.models.Id
import esw.ocs.BaseTestSuite
import esw.ocs.api.models.Step
import esw.ocs.dsl.Script
import org.mockito.Mockito.{verify, when}

import scala.concurrent.Future

class EngineTest extends BaseTestSuite {
  private implicit val test: ActorSystem = ActorSystem("test")
  private implicit val mat: Materializer = ActorMaterializer()

  private class Mocks {
    val sequenceOperator: SequenceOperator = mock[SequenceOperator]
    val script: Script                     = mock[Script]
    val cmd: SequenceCommand               = mock[SequenceCommand]
    val id: Id                             = mock[Id]
  }

  "Engine" must {
    "execute script with available commands" in {
      val mocks = new Mocks
      import mocks._

      val step1 = mock[Step]
      when(step1.command).thenReturn(cmd)
      when(step1.id).thenReturn(id)

      val step2 = mock[Step]
      when(step2.command).thenReturn(cmd)
      when(step2.id).thenReturn(id)

      val engine = new Engine()
      when(sequenceOperator.pullNext).thenReturn(Future.successful(step1), Future.successful(step2))
      when(sequenceOperator.readyToExecuteNext).thenReturn(Future.successful(Done))
      engine.start(sequenceOperator, script)
      eventually(verify(script).execute(step1.command))
      eventually(verify(script).execute(step2.command))
    }

    "update command response with Error when script's execute call throws exception" in {
      val mocks = new Mocks
      import mocks._

      val errorMsg = "error message"
      val step     = mock[Step]
      when(step.command).thenReturn(cmd)
      when(step.id).thenReturn(id)

      val engine = new Engine()
      when(sequenceOperator.pullNext).thenReturn(Future.successful(step))
      when(script.execute(step.command)).thenReturn(Future.failed(new RuntimeException(errorMsg)))
      engine.start(sequenceOperator, script)
      eventually(verify(sequenceOperator).update(Error(step.id, errorMsg)))
    }
  }
}
