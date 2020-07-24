package esw.ocs.api.actor.client

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import csw.command.client.extensions.AkkaLocationExt.RichAkkaLocation
import csw.location.api.models.{AkkaLocation, HttpLocation, Location, TcpLocation}
import esw.ocs.api.SequencerApi
import esw.ocs.api.client.SequencerClient
import esw.ocs.api.codecs.SequencerHttpCodecs
import esw.ocs.api.protocol.{SequencerPostRequest, SequencerWebsocketRequest}
import msocket.api.ContentType
import msocket.impl.post.HttpPostTransport
import msocket.impl.ws.WebsocketTransport

object SequencerApiFactory extends SequencerHttpCodecs {

  def make(componentLocation: Location)(implicit actorSystem: ActorSystem[_]): SequencerApi =
    componentLocation match {
      case _: TcpLocation             => throw new RuntimeException("Only AkkaLocation and HttpLocation can be used to access sequencer")
      case akkaLocation: AkkaLocation => new SequencerImpl(akkaLocation.sequencerRef)
      case httpLocation: HttpLocation => httpClient(httpLocation)
    }

  private def httpClient(httpLocation: HttpLocation)(implicit actorSystem: ActorSystem[_]): SequencerClient = {
    import actorSystem.executionContext

    val baseUri         = httpLocation.uri.toString
    val postUri         = Uri(baseUri).withPath(Path("/post-endpoint")).toString()
    val webSocketUri    = Uri(baseUri).withScheme("ws").withPath(Path("/websocket-endpoint")).toString()
    val postClient      = new HttpPostTransport[SequencerPostRequest](postUri, ContentType.Json, () => None)
    val websocketClient = new WebsocketTransport[SequencerWebsocketRequest](webSocketUri, ContentType.Json)
    new SequencerClient(postClient, websocketClient)
  }
}
