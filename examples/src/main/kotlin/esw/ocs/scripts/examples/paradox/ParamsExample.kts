@file:Suppress("UNUSED_VARIABLE")

package esw.ocs.scripts.examples.paradox

import csw.params.commands.Setup
import csw.params.core.generics.Key
import csw.params.core.generics.Parameter
import csw.params.javadsl.JUnits.watt
import esw.ocs.dsl.core.script
import esw.ocs.dsl.highlevel.models.WFOS
import esw.ocs.dsl.params.*

script {

    val galilAssembly = Assembly(WFOS, "FilterWheel")

    onSetup("setup-wfos") {
        // #creating-params
        //#getting-values
        val temperatureKey: Key<Int> = intKey("temperature")

        //#getting-values
        val temperatureParam: Parameter<Int> = temperatureKey.set(1, 2, 3)

        // with values as Array
        val encoderKey: Key<Int> = intKey("encoder")
        val encoderValues: Array<Int> = arrayOf(1, 2, 3)
        val encoderParam: Parameter<Int> = encoderKey.set(*encoderValues)

        // with units
        val powerKey: Key<Double> = doubleKey("power")
        val values: Array<Double> = arrayOf(1.1, 2.2, 3.3)
        val powerParam: Parameter<Double> = powerKey.set(values, watt())

        // adding params to command
        val setupCommand: Setup = Setup("ESW.iris_darkNight", "move").madd(temperatureParam, encoderParam)
        // #creating-params

        //#getting-values
        // extracting a param from Params instance
        val params: Params = setupCommand.params
        val temperatureParameter: Parameter<Int>? = params.kGet(temperatureKey)

        // extracting a param directly from the command
        val temperatureParameter2: Parameter<Int>? = setupCommand.kGet(temperatureKey)

        info("Temperature: ${temperatureParameter?.values()}")
        //#getting-values

        //#remove
        // remove param from params by key
        val updatedParams: Params = params.remove(temperatureKey)

        // remove param from params
        val updatedParams2: Params = params.remove(temperatureParameter)

        // remove param from command by key
        val updatedCommand: Setup = setupCommand.remove(temperatureKey)

        // remove param from command
        val updatedCommand2: Setup = setupCommand.remove(temperatureParameter)
        //#remove

        //#exists
        // check if parameter with specified key exists in Params
        val temperatureKeyExists: Boolean = params.exists(temperatureKey)

        // check if parameter with specified key exists directly from command
        val temperatureKeyExists2: Boolean = setupCommand.exists(temperatureKey)
        //#exists

    }

    // #adding-params
    onSetup("setup-wfos") { command ->
        val params: Params = command.params.madd(intKey("temperature").set(10))
        val assemblyCommand: Setup = Setup("ESW.iris_darkNight", "move").madd(params)
        galilAssembly.submit(assemblyCommand)
    }
    // #adding-params

}