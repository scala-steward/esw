package esw.gateway.api.protocol

import csw.alarm.models.AlarmSeverity
import csw.alarm.models.Key.AlarmKey
import csw.command.api.messages.CommandServiceHttpMessage
import csw.location.models.ComponentId
import csw.logging.models.Level
import csw.params.events.{Event, EventKey}
import csw.prefix.models.Prefix
import esw.ocs.api.protocol.SequencerPostRequest

sealed trait PostRequest

object PostRequest {
  case class ComponentCommand(componentId: ComponentId, command: CommandServiceHttpMessage)             extends PostRequest
  case class SequencerCommand(componentId: ComponentId, command: SequencerPostRequest)                  extends PostRequest
  case class PublishEvent(event: Event)                                                                 extends PostRequest
  case class GetEvent(eventKeys: Set[EventKey])                                                         extends PostRequest
  case class SetAlarmSeverity(alarmKey: AlarmKey, severity: AlarmSeverity)                              extends PostRequest
  case class Log(prefix: Prefix, level: Level, message: String, metadata: Map[String, Any] = Map.empty) extends PostRequest
  case class SetLogLevel(componentId: ComponentId, level: Level)                                        extends PostRequest
  case class GetLogMetadata(componentId: ComponentId)                                                   extends PostRequest
}
