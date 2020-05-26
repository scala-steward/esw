package esw.ocs.script

import akka.actor.testkit.typed.scaladsl.TestProbe
import csw.params.commands.CommandResponse.Started
import csw.params.commands.{CommandName, Sequence, Setup}
import csw.params.core.generics.KeyType.StringKey
import csw.params.events.{Event, EventKey, SystemEvent}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.LGSF
import csw.testkit.scaladsl.CSWService.EventServer
import esw.ocs.api.SequencerApi
import esw.ocs.testkit.EswTestKit

class NewSequencerHandlerIntegrationTest extends EswTestKit(EventServer) {

  private val obsMode  = "darknight"
  private val command  = Setup(Prefix("esw.test"), CommandName("command-1"), None)
  private val sequence = Sequence(command)

  private var sequencer: SequencerApi = _

  //create testprobe for the event publishing in command-1 handler in the script
  private val commandHandlerEventKeys                    = Set(EventKey("LGSF.darknight.command1"))
  private var commandHandlerEventProbe: TestProbe[Event] = _

  //create testprobe for the event publishing in onNewSequence handler in the script
  private val newSequenceHandlerEventKeys                             = Set(EventKey("LGSF.darknight.NewSequenceHandler"))
  private var newSequenceHandlerInitializationProbe: TestProbe[Event] = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    spawnSequencer(LGSF, obsMode)
    sequencer = sequencerClient(LGSF, obsMode)
    commandHandlerEventProbe = createTestProbe(commandHandlerEventKeys)
    newSequenceHandlerInitializationProbe = createTestProbe(newSequenceHandlerEventKeys)
  }

  "Sequencer" must {
    "run the new Sequencer handler before starting the new Sequence | ESW-303" in {
      val submitResponseF = sequencer.submit(sequence)

      //assert sequence has not initialized
      commandHandlerEventProbe.expectNoMessage()

      val sequenceHandlerEventParam          = StringKey.make("onNewSequence").set("Started")
      val sequenceHandlerInitializationEvent = newSequenceHandlerInitializationProbe.expectMessageType[SystemEvent]

      //assert onNewSequence handler has started
      sequenceHandlerInitializationEvent.paramSet.head shouldBe sequenceHandlerEventParam

      Thread.sleep(500)
      //onNewSequence handler completed

      val newSequenceEventParam       = StringKey.make("sequence-command-1").set("Started")
      val sequenceInitializationEvent = commandHandlerEventProbe.expectMessageType[SystemEvent]
      //assert sequence has started
      sequenceInitializationEvent.paramSet.head shouldBe newSequenceEventParam

      submitResponseF.futureValue shouldBe a[Started]
    }

    "not run the new Sequencer handler on loadSequence command | ESW-303" in {
      sequencer.loadSequence(sequence)

      //assert sequence has not initialized
      commandHandlerEventProbe.expectNoMessage()

      //assert onNewSequence handler has not initialized since sequence is only loaded
      newSequenceHandlerInitializationProbe.expectNoMessage()

      // starting the sequence
      val submitResponseF = sequencer.startSequence()

      // it should first trigger the onNewSequence handler since sequence has started
      val sequenceHandlerEventParam          = StringKey.make("onNewSequence").set("Started")
      val sequenceHandlerInitializationEvent = newSequenceHandlerInitializationProbe.expectMessageType[SystemEvent]

      //assert onNewSequence handler has started
      sequenceHandlerInitializationEvent.paramSet.head shouldBe sequenceHandlerEventParam

      Thread.sleep(500)
      //onNewSequence handler completed

      //now sequence commands execution should start
      val newSequenceEventParam       = StringKey.make("sequence-command-1").set("Started")
      val sequenceInitializationEvent = commandHandlerEventProbe.expectMessageType[SystemEvent]

      //assert sequence has started
      sequenceInitializationEvent.paramSet.head shouldBe newSequenceEventParam

      submitResponseF.futureValue shouldBe a[Started]
    }
  }
}
