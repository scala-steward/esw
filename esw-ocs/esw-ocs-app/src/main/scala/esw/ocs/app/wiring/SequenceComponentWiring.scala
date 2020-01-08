package esw.ocs.app.wiring

import akka.actor.typed.SpawnProtocol.Spawn
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, Props}
import akka.util.Timeout
import csw.location.models.AkkaLocation
import csw.logging.api.scaladsl.Logger
import csw.logging.client.scaladsl.LoggerFactory
import csw.prefix.models.{Prefix, Subsystem}
import esw.http.core.wiring.{ActorRuntime, CswWiring}
import esw.ocs.api.protocol.ScriptError
import esw.ocs.impl.core.SequenceComponentBehavior
import esw.ocs.impl.internal.{SequenceComponentRegistration, SequencerServerFactory, Timeouts}
import esw.ocs.impl.messages.SequenceComponentMsg
import esw.ocs.impl.syntax.FutureSyntax.FutureOps

import scala.concurrent.Future

// $COVERAGE-OFF$
private[ocs] class SequenceComponentWiring(
    subsystem: Subsystem,
    name: Option[String],
    sequencerServerFactory: SequencerServerFactory
) {
  private val registrationRetryCount = 10

  lazy val cswWiring = new CswWiring()
  import cswWiring._
  import cswWiring.actorRuntime._
  lazy val actorRuntime: ActorRuntime = cswWiring.actorRuntime

  implicit lazy val timeout: Timeout = Timeouts.DefaultTimeout

  def sequenceComponentFactory(sequenceComponentPrefix: Prefix): Future[ActorRef[SequenceComponentMsg]] = {
    val loggerFactory                   = new LoggerFactory(sequenceComponentPrefix)
    val sequenceComponentLogger: Logger = loggerFactory.getLogger

    sequenceComponentLogger.info(s"Starting sequence component with name: $sequenceComponentPrefix")
    typedSystem ? { x =>
      Spawn(
        SequenceComponentBehavior.behavior(sequenceComponentPrefix, sequenceComponentLogger, sequencerServerFactory),
        sequenceComponentPrefix.value,
        Props.empty,
        x
      )
    }
  }

  private lazy val sequenceComponentRegistration =
    new SequenceComponentRegistration(subsystem, name, locationService, sequenceComponentFactory)

  def start(): Either[ScriptError, AkkaLocation] =
    sequenceComponentRegistration.registerSequenceComponent(registrationRetryCount).block

}
// $COVERAGE-ON$
