package esw.ocs.app

import akka.Done
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import csw.command.client.internal.SequencerCommandServiceImpl
import csw.location.api.extensions.URIExtension.RichURI
import csw.location.models.Connection.AkkaConnection
import csw.location.models.{AkkaLocation, ComponentId, ComponentType}
import csw.params.commands.CommandResponse.Completed
import csw.params.commands.{CommandName, Sequence, Setup}
import csw.params.core.models.{Prefix, Subsystem}
import esw.ocs.api.protocol.{ScriptError, ScriptResponse}
import esw.ocs.app.SequencerAppCommand.{SequenceComponent, Sequencer}
import esw.ocs.impl.messages.SequenceComponentMsg
import esw.ocs.impl.messages.SequenceComponentMsg.{LoadScript, UnloadScript}
import esw.ocs.testkit.EswTestKit

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SequencerAppIntegrationTest extends EswTestKit {

  override def afterEach(): Unit = locationService.unregisterAll()

  "SequenceComponent command" must {
    "start sequence component with provided subsystem and name and register it with location service | ESW-102, ESW-136, ESW-103, ESW-147, ESW-151, ESW-214" in {
      val subsystem             = Subsystem.ESW
      val name: String          = "primary"
      val expectedSequencerName = "ESW.primary@esw@darknight"

      // start Sequence Component
      SequencerApp.run(SequenceComponent(subsystem, Some(name)), enableLogging = false)

      // verify Sequence component is started and registered with location service
      val sequenceCompLocation: AkkaLocation = resolveSequenceComponentLocation(s"$subsystem.$name")

      sequenceCompLocation.connection shouldEqual AkkaConnection(ComponentId("ESW.primary", ComponentType.SequenceComponent))
      sequenceCompLocation.prefix shouldEqual Prefix("ESW.primary")

      // LoadScript
      val seqCompRef = sequenceCompLocation.uri.toActorRef.unsafeUpcast[SequenceComponentMsg]
      val probe      = TestProbe[ScriptResponse]
      seqCompRef ! LoadScript("esw", "darknight", probe.ref)

      // verify that loaded sequencer is started and able to process sequence command
      val response          = probe.expectMessageType[ScriptResponse]
      val sequencerLocation = response.response.rightValue

      //verify sequencerName has SequenceComponentName
      val actualSequencerName: String = sequencerLocation.connection.componentId.name
      actualSequencerName shouldEqual expectedSequencerName

      // verify Sequencer is started and registered with location service with expected name
      val sequencerLocationCheck: AkkaLocation = resolveSequencerLocation(expectedSequencerName)
      sequencerLocationCheck shouldEqual sequencerLocation

      val commandService = new SequencerCommandServiceImpl(sequencerLocation)
      val setup          = Setup(Prefix("wfos.home.datum"), CommandName("command-1"), None)
      val sequence       = Sequence(setup)
      commandService.submitAndWait(sequence).futureValue shouldBe a[Completed]

      // UnloadScript
      val probe2 = TestProbe[Done]
      seqCompRef ! UnloadScript(probe2.ref)
      probe2.expectMessage(Done)
    }

    "start sequence component and register with automatically generated random uniqueIDs if name is not provided| ESW-144" in {
      val subsystem = Subsystem.ESW

      SequencerApp.run(SequenceComponent(subsystem, None), enableLogging = false)

      val sequenceComponentLocation = locationService.list(ComponentType.SequenceComponent).futureValue.head

      //assert that componentName and prefix contain subsystem provided
      sequenceComponentLocation.connection.componentId.name.contains("ESW.ESW_") shouldEqual true
      sequenceComponentLocation.asInstanceOf[AkkaLocation].prefix.prefix.contains("ESW.ESW_") shouldEqual true
    }

    "start sequence component concurrently and register with automatically generated random uniqueIDs if name is not provided| ESW-144" in {
      val subsystem = Subsystem.ESW

      //register sequence component with same subsystem concurrently
      Future { SequencerApp.run(SequenceComponent(subsystem, None), enableLogging = false) }
      Future { SequencerApp.run(SequenceComponent(subsystem, None), enableLogging = false) }
      Future { SequencerApp.run(SequenceComponent(subsystem, None), enableLogging = false) }

      //Wait till futures registering sequence component complete
      Thread.sleep(5000)

      val sequenceComponentLocations = locationService.list(ComponentType.SequenceComponent).futureValue

      //assert if all 3 sequence components are registered
      locationService.list(ComponentType.SequenceComponent).futureValue.length shouldEqual 3
      sequenceComponentLocations.foreach { location =>
        {
          //assert that componentName and prefix contain subsystem provided
          location.connection.componentId.name.contains("ESW.ESW_") shouldEqual true
          location.asInstanceOf[AkkaLocation].prefix.prefix.contains("ESW.ESW_") shouldEqual true
        }
      }
    }

    "return ScriptError when script configuration is not provided| ESW-102, ESW-136" in {
      val subsystem        = Subsystem.ESW
      val name             = "primary"
      val invalidPackageId = "invalid_package"
      val observingMode    = "darknight"

      // start Sequence Component
      SequencerApp.run(SequenceComponent(subsystem, Some(name)), enableLogging = false)

      // verify Sequence component is started and registered with location service
      val sequenceCompLocation: AkkaLocation = resolveSequenceComponentLocation(s"$subsystem.$name")

      sequenceCompLocation.connection shouldEqual AkkaConnection(ComponentId("ESW.primary", ComponentType.SequenceComponent))
      sequenceCompLocation.prefix shouldEqual Prefix("ESW.primary")

      val timeout = Timeout(10.seconds)
      // LoadScript
      val seqCompRef: ActorRef[SequenceComponentMsg] = sequenceCompLocation.uri.toActorRef.unsafeUpcast[SequenceComponentMsg]
      val loadScriptResponse: Future[ScriptResponse] =
        seqCompRef.ask((ref: ActorRef[ScriptResponse]) => LoadScript(invalidPackageId, observingMode, ref))(
          timeout,
          schedulerFromActorSystem
        )

      val response: Either[ScriptError, AkkaLocation] = loadScriptResponse.futureValue.response

      response match {
        case Left(v) =>
          v shouldEqual ScriptError(s"Script configuration missing for $invalidPackageId with $observingMode")
        case Right(_) => throw new RuntimeException("test failed as this test expects ScriptError")
      }
    }
  }

  "Sequencer command" must {
    "start sequencer with provided id, mode and register it with location service | ESW-102, ESW-136, ESW-103, ESW-147, ESW-151" in {
      val subsystem     = Subsystem.ESW
      val name          = Some("primary")
      val packageId     = Some("esw")
      val observingMode = "darknight"
      val sequencerName = "ESW.primary@esw@darknight"

      // start Sequencer
      SequencerApp.run(Sequencer(subsystem, name, packageId, observingMode), enableLogging = false)

      // verify sequence component is started
      val sequenceComponentName     = s"$subsystem.${name.value}"
      val sequenceComponentLocation = resolveSequenceComponentLocation(sequenceComponentName)
      sequenceComponentLocation.connection.componentId.name shouldBe sequenceComponentName

      // verify that sequencer is started and able to process sequence command
      val connection        = AkkaConnection(ComponentId(sequencerName, ComponentType.Sequencer))
      val sequencerLocation = locationService.resolve(connection, 5.seconds).futureValue.value

      sequencerLocation.connection.componentId.name shouldBe sequencerName

      val commandService = new SequencerCommandServiceImpl(sequencerLocation)
      val setup          = Setup(Prefix("wfos.home.datum"), CommandName("command-1"), None)
      val sequence       = Sequence(setup)
      commandService.submitAndWait(sequence).futureValue shouldBe a[Completed]
    }

    "start sequencer with provided mandatory subsystem, mode register it with location service | ESW-103" in {
      val subsystem     = Subsystem.ESW
      val observingMode = "darknight"

      // start Sequencer
      SequencerApp.run(Sequencer(subsystem, None, None, observingMode), enableLogging = false)

      val sequenceComponentLocation = locationService.list(ComponentType.SequenceComponent).futureValue.head

      //assert that componentName and prefix contain subsystem provided
      sequenceComponentLocation.connection.componentId.name.contains("ESW.ESW_") shouldEqual true
      sequenceComponentLocation.asInstanceOf[AkkaLocation].prefix.prefix.contains("ESW.ESW_") shouldEqual true

      val sequenceComponentName = sequenceComponentLocation.connection.componentId.name

      //sequencer name will have sequence component name and optional packageId is defaulted to subsystem
      val sequencerName = s"$sequenceComponentName@esw@darknight"
      // verify that sequencer is started and able to process sequence command
      resolveSequencerLocation(sequencerName)
      val sequencerLocation = resolveSequencerLocation(sequencerName)

      sequencerLocation.connection.componentId.name shouldBe sequencerName
    }

    "throw exception if ScriptError is returned | ESW-102, ESW-136" in {
      val subsystem        = Subsystem.ESW
      val name             = Some("primary")
      val invalidPackageId = "invalid package"
      val observingMode    = "darknight"

      val exception = intercept[RuntimeException] {
        SequencerApp.run(Sequencer(subsystem, name, Some(invalidPackageId), observingMode), enableLogging = false)
      }

      exception.getMessage shouldEqual s"Failed to start with error: ScriptError(Script configuration missing for $invalidPackageId with $observingMode)"
    }
  }
}
