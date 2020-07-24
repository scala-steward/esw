package esw.sm.app

import java.nio.file.Path

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.SpawnProtocol.Spawn
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem, Props, SpawnProtocol}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import csw.aas.http.SecurityDirectives
import csw.config.api.scaladsl.ConfigClientService
import csw.config.client.commons.ConfigUtils
import csw.config.client.scaladsl.ConfigClientFactory
import csw.location.api.AkkaRegistrationFactory
import csw.location.api.extensions.ActorExtension._
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.location.api.scaladsl.LocationService
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.api.scaladsl.Logger
import csw.logging.client.scaladsl.LoggerFactory
import csw.network.utils.SocketUtils
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import esw.commons.Timeouts
import esw.commons.utils.location.EswLocationError.RegistrationError
import esw.commons.utils.location.LocationServiceUtil
import esw.http.core.wiring.{ActorRuntime, HttpService, Settings}
import esw.sm.api.SequenceManagerApi
import esw.sm.api.actor.client.SequenceManagerApiFactory
import esw.sm.api.actor.messages.SequenceManagerMsg
import esw.sm.api.codecs.SequenceManagerHttpCodec
import esw.sm.handler.SequenceManagerPostHandler
import esw.sm.impl.config.SequenceManagerConfigParser
import esw.sm.impl.core.SequenceManagerBehavior
import esw.sm.impl.utils._
import msocket.impl.RouteFactory
import msocket.impl.post.PostRouteFactory

import scala.async.Async.{async, await}
import scala.concurrent.{Await, Future}

class SequenceManagerWiring(obsModeConfigPath: Path, provisionConfigPath: Path) {
  private[sm] lazy val actorSystem: ActorSystem[SpawnProtocol.Command] =
    ActorSystemFactory.remote(SpawnProtocol(), "sequencer-manager")
  lazy val actorRuntime = new ActorRuntime(actorSystem)
  import actorRuntime._
  private implicit val timeout: Timeout = Timeouts.DefaultTimeout

  private val prefix = Prefix(ESW, "sequence_manager")

  private lazy val locationService: LocationService         = HttpLocationServiceFactory.makeLocalClient(actorSystem)
  private lazy val configClientService: ConfigClientService = ConfigClientFactory.clientApi(actorSystem, locationService)
  private lazy val configUtils: ConfigUtils                 = new ConfigUtils(configClientService)(actorSystem)
  private lazy val loggerFactory                            = new LoggerFactory(prefix)
  private lazy val logger: Logger                           = loggerFactory.getLogger

  private lazy val locationServiceUtil        = new LocationServiceUtil(locationService)
  private lazy val sequenceComponentAllocator = new SequenceComponentAllocator()
  private lazy val sequenceComponentUtil      = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator)
  private lazy val agentAllocator             = new AgentAllocator()
  private lazy val agentUtil                  = new AgentUtil(locationServiceUtil, sequenceComponentUtil, agentAllocator)
  private lazy val sequencerUtil              = new SequencerUtil(locationServiceUtil, sequenceComponentUtil)

  private lazy val configParser = new SequenceManagerConfigParser(configUtils)
  private lazy val obsModeConfig =
    Await.result(configParser.readObsModeConfig(obsModeConfigPath, isLocal = true), Timeouts.DefaultTimeout)
  private val provisionConfigProvider = () => configParser.readProvisionConfig(provisionConfigPath, isLocal = true)

  private lazy val sequenceManagerBehavior =
    new SequenceManagerBehavior(
      obsModeConfig,
      provisionConfigProvider,
      locationServiceUtil,
      agentUtil,
      sequencerUtil,
      sequenceComponentUtil
    )

  private lazy val sequenceManagerRef: ActorRef[SequenceManagerMsg] = Await.result(
    actorSystem ? (Spawn(sequenceManagerBehavior.setup, "sequence-manager", Props.empty, _)),
    Timeouts.DefaultTimeout
  )

  private lazy val config     = actorSystem.settings.config
  private lazy val connection = AkkaConnection(ComponentId(prefix, ComponentType.Service))
  private lazy val refURI     = sequenceManagerRef.toURI
  private lazy val sequenceManager: SequenceManagerApi =
    SequenceManagerApiFactory.makeAkkaClient(AkkaLocation(connection, refURI))

  private[sm] lazy val securityDirectives = SecurityDirectives(actorSystem.settings.config, locationService)
  private lazy val postHandler            = new SequenceManagerPostHandler(sequenceManager, securityDirectives)

  import SequenceManagerHttpCodec._
  lazy val routes: Route = RouteFactory.combine(metricsEnabled = false)(new PostRouteFactory("post-endpoint", postHandler))

  private lazy val settings    = new Settings(Some(SocketUtils.getFreePort), Some(prefix), config, ComponentType.Service)
  private lazy val httpService = new HttpService(logger, locationService, routes, settings, actorRuntime)

  def start(): Either[RegistrationError, AkkaLocation] = {
    logger.info(s"Starting Sequence Manager with prefix: $prefix")
    //start http server and register it with location service
    Await.result(httpService.registeredLazyBinding, Timeouts.DefaultTimeout)

    val registration = AkkaRegistrationFactory.make(connection, refURI)
    val loc          = Await.result(locationServiceUtil.register(registration), Timeouts.DefaultTimeout)

    logger.info(s"Successfully started Sequence Manager with prefix: $prefix")
    loc
  }

  private def shutdownHttpService: Future[Done] =
    async {
      logger.debug("Shutting down Sequence Manager http service")
      val (serverBinding, registrationResult) = await(httpService.registeredLazyBinding)
      val eventualTerminated                  = serverBinding.terminate(Timeouts.DefaultTimeout)
      val eventualDone                        = registrationResult.unregister()
      await(eventualTerminated.flatMap(_ => eventualDone))
    }

  def shutdown(reason: CoordinatedShutdown.Reason): Future[Done] =
    shutdownHttpService.flatMap(_ => CoordinatedShutdown(actorSystem).run(reason))
}

private[sm] object SequenceManagerWiring {
  def apply(
      obsModeConfig: Path,
      provisionConfig: Path,
      _actorSystem: ActorSystem[SpawnProtocol.Command],
      _securityDirectives: SecurityDirectives
  ): SequenceManagerWiring =
    new SequenceManagerWiring(obsModeConfig, provisionConfig) {
      override private[sm] lazy val actorSystem        = _actorSystem
      override private[sm] lazy val securityDirectives = _securityDirectives
    }
}
