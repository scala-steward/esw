package esw.ocs.scripts.examples.testData

import csw.location.models.ComponentType.`Assembly$`
import csw.params.commands.*
import csw.params.core.models.Id
import csw.params.core.models.Prefix
import csw.time.core.models.UTCTime
import esw.ocs.dsl.core.script
import esw.ocs.dsl.params.runId
import kotlinx.coroutines.delay
import scala.Option
import scala.collection.immutable.HashSet
import scala.jdk.javaapi.CollectionConverters
import java.util.*

script {

    handleSetup("command-1") { command ->
        // To avoid sequencer to finish immediately so that other Add, Append command gets time
        delay(200)
        addOrUpdateCommand(CommandResponse.Completed(command.runId))
    }

    handleSetup("command-2") { command ->
        addOrUpdateCommand(CommandResponse.Completed(command.runId))
    }

    handleSetup("command-3") { command ->
        addOrUpdateCommand(CommandResponse.Completed(command.runId))
    }

    handleSetup("command-4") { command ->
        // try sending concrete sequence
        val command4 = Setup(
            Id("testCommandIdString123"),
            Prefix("TCS.test"),
            CommandName("command-to-assert-on"),
            Option.apply(null),
            HashSet()
        )
        val sequence = Sequence(
            Id("testSequenceIdString123"),
            CollectionConverters.asScala(Collections.singleton<SequenceCommand>(command4)).toSeq()
        )

        // ESW-145, ESW-195
        submitSequence("TCS", "testObservingMode4", sequence)
        addOrUpdateCommand(CommandResponse.Completed(command.runId()))
    }

    handleSetup("fail-command") { command ->
        addOrUpdateCommand(CommandResponse.Error(command.runId(), command.commandName().name()))
    }

    handleDiagnosticMode { startTime, hint ->
        when {
            startTime is UTCTime && hint is String ->
                // do some actions to go to diagnostic mode based on hint
                // fixme : find easy way to use Scala case objects in Script
                diagnosticModeForComponent("test", `Assembly$`.`MODULE$`, startTime, hint)
        }
    }

    handleOperationsMode {
        // do some actions to go to operations mode
        operationsModeForComponent("test", `Assembly$`.`MODULE$`)
    }
}
