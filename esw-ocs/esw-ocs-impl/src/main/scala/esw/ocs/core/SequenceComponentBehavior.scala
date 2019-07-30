package esw.ocs.core

import akka.Done
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import csw.location.models.AkkaLocation
import esw.ocs.api.models.messages.SequenceComponentMsg
import esw.ocs.api.models.messages.SequenceComponentMsg.{GetStatus, LoadScript, UnloadScript}
import esw.ocs.api.models.messages.SequenceComponentResponse.{GetStatusResponse, LoadScriptResponse}
import esw.ocs.api.models.messages.error.RegistrationError
import esw.ocs.internal.SequencerWiring

object SequenceComponentBehavior {

  def behavior: Behavior[SequenceComponentMsg] = {

    lazy val idle: Behavior[SequenceComponentMsg] = Behaviors.receiveMessage[SequenceComponentMsg] {
      case LoadScript(sequencerId, observingMode, replyTo) =>
        val wiring             = new SequencerWiring(sequencerId, observingMode)
        val registrationResult = wiring.start()
        replyTo ! LoadScriptResponse(registrationResult)
        registrationResult.map(x => running(wiring, x)).getOrElse(Behaviors.same)
      case GetStatus(replyTo) =>
        replyTo ! GetStatusResponse(None)
        Behaviors.same
      case UnloadScript(replyTo) =>
        replyTo ! Done
        Behaviors.same
    }

    def running(wiring: SequencerWiring, location: AkkaLocation): Behavior[SequenceComponentMsg] =
      Behaviors.receive[SequenceComponentMsg] { (ctx, msg) =>
        import ctx.executionContext

        msg match {
          case UnloadScript(replyTo) =>
            wiring.shutDown().foreach(_ => replyTo ! Done)
            idle
          case GetStatus(replyTo) =>
            replyTo ! GetStatusResponse(Some(location))
            Behaviors.same
          case LoadScript(_, _, replyTo) =>
            replyTo ! LoadScriptResponse(Left(RegistrationError("Loading script failed: Sequencer already running")))
            Behaviors.same
        }
      }
    idle
  }
}
