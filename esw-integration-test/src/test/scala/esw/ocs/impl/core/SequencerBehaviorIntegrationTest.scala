package esw.ocs.impl.core

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.{ActorRef, ActorSystem, SpawnProtocol}
import csw.command.client.messages.sequencer.SequencerMsg
import csw.params.commands.{CommandName, Sequence, Setup}
import csw.params.core.models.{Id, Prefix}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import esw.ocs.api.BaseTestSuite
import esw.ocs.api.protocol.{LoadSequenceResponse, Ok, Unhandled}
import esw.ocs.app.wiring.SequencerWiring
import esw.ocs.impl.messages.SequencerMessages.{LoadSequence, SubmitSequence}

import scala.concurrent.duration.DurationDouble

class SequencerBehaviorIntegrationTest extends ScalaTestFrameworkTestKit with BaseTestSuite {

  implicit val actorSystem: ActorSystem[SpawnProtocol] = frameworkTestKit.actorSystem

  private val ocsPackageId     = "esw"
  private val ocsObservingMode = "darknight"

  private var ocsWiring: SequencerWiring           = _
  private var ocsSequencer: ActorRef[SequencerMsg] = _

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds)

  override def beforeEach(): Unit = {
    ocsWiring = new SequencerWiring(ocsPackageId, ocsObservingMode, None)
    ocsSequencer = ocsWiring.sequencerRef
  }

  override def afterEach(): Unit = {
    ocsWiring.sequencerServer.shutDown().futureValue
  }

  "Sequencer" must {
    "not receive sequence when already processing a sequence | ESW-145" in {
      val command                   = Setup(Prefix("TCS.test"), CommandName("test-sequencer-hierarchy"), None)
      val submitResponseProbe       = TestProbe[LoadSequenceResponse]
      val loadSequenceResponseProbe = TestProbe[LoadSequenceResponse]
      val sequenceId                = Id()
      val sequence                  = Sequence(sequenceId, Seq(command))

      ocsSequencer ! SubmitSequence(sequence, submitResponseProbe.ref)
      ocsSequencer ! LoadSequence(sequence, loadSequenceResponseProbe.ref)

      // response received by irisSequencer
      submitResponseProbe.expectMessage(Ok)
      loadSequenceResponseProbe.expectMessage(Unhandled("InProgress", "LoadSequence"))
    }
  }
}
