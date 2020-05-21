package esw.agent.client

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.serialization.Serializer
import csw.commons.cbor.CborAkkaSerializer
import esw.agent.api.codecs.AgentCodecs
import esw.agent.api.{AgentAkkaSerializable, AgentCommand, Response}

// $COVERAGE-OFF$
class AgentAkkaSerializer(_actorSystem: ExtendedActorSystem)
    extends CborAkkaSerializer[AgentAkkaSerializable]
    with AgentCodecs
    with Serializer {

  override def identifier: Int = 26726

  override implicit def actorSystem: ActorSystem[_] = _actorSystem.toTyped

  register[AgentCommand]
  register[Response]
}
// $COVERAGE-ON$
