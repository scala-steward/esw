package esw.ocs.impl.script

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import com.typesafe.config.Config
import csw.alarm.api.javadsl.IAlarmService
import csw.event.api.javadsl.IEventService
import csw.logging.api.javadsl.ILogger
import csw.prefix.models.Prefix
import esw.ocs.impl.SequencerActorProxyFactory
import esw.ocs.impl.core.SequenceOperator

class ScriptContext(
    val prefix: Prefix,
    val jLogger: ILogger,
    val sequenceOperatorFactory: () => SequenceOperator,
    val actorSystem: ActorSystem[SpawnProtocol.Command],
    val eventService: IEventService,
    val alarmService: IAlarmService,
    val sequencerApiFactory: SequencerActorProxyFactory,
    val config: Config
)
