package esw.ocs.framework.core

import akka.Done
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import csw.location.api.models.AkkaLocation
import esw.ocs.framework.SequencerWiring
import esw.ocs.framework.api.models.messages.SequenceComponentMsg
import esw.ocs.framework.api.models.messages.SequenceComponentMsg.{GetStatus, LoadScript, StopScript}
import esw.ocs.framework.exceptions.SequencerAlreadyRunningException

import scala.util.Failure

object SequenceComponent {

  def behavior: Behavior[SequenceComponentMsg] = {

    lazy val idle: Behavior[SequenceComponentMsg] = Behaviors.receiveMessage[SequenceComponentMsg] {
      case LoadScript(sequencerId, observingMode, sender) =>
        val wiring   = new SequencerWiring(sequencerId, observingMode)
        val location = wiring.start()
        sender ! location
        location.map(x => running(wiring, x)).getOrElse(Behaviors.same)
      case GetStatus(sender) =>
        sender ! None
        Behaviors.same
      case StopScript(sender) =>
        sender ! Done
        Behaviors.same
    }

    def running(wiring: SequencerWiring, location: AkkaLocation): Behavior[SequenceComponentMsg] =
      Behaviors.receiveMessage[SequenceComponentMsg] {
        case StopScript(sender) =>
          wiring.shutDown()
          sender ! Done
          idle
        case GetStatus(sender) =>
          sender ! Some(location)
          Behaviors.same
        case LoadScript(_, _, sender) =>
          sender ! Failure(SequencerAlreadyRunningException)
          Behaviors.same
      }
    idle
  }
}
