package spray.can.server.websockets

import spray.can.{Http, HttpManager, HttpExt}
import akka.actor._
import spray.can.server._
import spray.can.client.{HttpClientSettingsGroup, HttpHostConnector, ClientConnectionSettings}
import spray.http.{Uri, HttpRequest}
import scala.util.control.NonFatal
import spray.can.server.StatsSupport.StatsHolder
import spray.io._
import spray.http.HttpRequest
import akka.actor.Terminated
import scala.concurrent.duration.Duration
import spray.can.server.websockets.SocketServer.Connected

object Sockets extends ExtensionKey[SocketExt]{
  case class Upgrade(frameHandler: ActorRef,
                     autoPingInterval: Duration,
                     pingGenerator: () => Array[Byte],
                     frameSizeLimit: Int) extends Command
}
class SocketExt(system: ExtendedActorSystem) extends HttpExt(system){
  override val manager = system.actorOf(
    props = Props(new SocketManager(Settings)) withDispatcher Settings.ManagerDispatcher,
    name = "IO-SOCKET")
}

class SocketManager(httpSettings: HttpExt#Settings) extends HttpManager(httpSettings){
  import httpSettings._
  private[this] val listenerCounter = Iterator from 0
  private[this] val groupCounter = Iterator from 0
  private[this] val hostConnectorCounter = Iterator from 0

  private[this] var settingsGroups = Map.empty[ClientConnectionSettings, ActorRef]
  private[this] var hostConnectors = Map.empty[Http.HostConnectorSetup, ActorRef]
  private[this] var listeners = Seq.empty[ActorRef]

  override def receive = withTerminationManagement {
    case request: HttpRequest ⇒
      try {
        val req = request.withEffectiveUri(securedConnection = false)
        val Uri.Authority(host, port, _) = req.uri.authority
        val effectivePort = if (port == 0) Uri.defaultPorts(req.uri.scheme) else port
        val connector = hostConnectorFor(Http.HostConnectorSetup(host.toString, effectivePort, req.uri.scheme == "https"))
        // never render absolute URI here
        connector.forward(req.copy(uri = req.uri.copy(scheme = "", authority = Uri.Authority.Empty)))
      } catch {
        case NonFatal(e) ⇒
          log.error("Illegal request: {}", e.getMessage)
          sender ! Status.Failure(e)
      }

    case (request: HttpRequest, setup: Http.HostConnectorSetup) ⇒
      hostConnectorFor(setup).forward(request)

    case setup: Http.HostConnectorSetup ⇒
      val connector = hostConnectorFor(setup)
      sender.tell(Http.HostConnectorInfo(connector, setup), connector)

    case connect: Http.Connect ⇒
      settingsGroupFor(ClientConnectionSettings(connect.settings)).forward(connect)

    case bind: Http.Bind ⇒
      val commander = sender
      listeners :+= context.watch {
        context.actorOf(
          props = Props(new HttpListener(commander, bind, httpSettings)) withDispatcher ListenerDispatcher,
          name = "listener-" + listenerCounter.next())
      }

    case cmd: Http.CloseAll ⇒ shutdownSettingsGroups(cmd, Set(sender))
  }

  override def withTerminationManagement(behavior: Receive): Receive = ({
    case ev @ Terminated(child) ⇒
      if (listeners contains child)
        listeners = listeners filter (_ != child)
      else if (hostConnectors exists (_._2 == child))
        hostConnectors = hostConnectors filter { _._2 != child }
      else
        settingsGroups = settingsGroups filter { _._2 != child }
      behavior.applyOrElse(ev, (_: Terminated) ⇒ ())

    case HttpHostConnector.DemandIdleShutdown ⇒
      hostConnectors = hostConnectors filter { _._2 != sender }
      sender ! PoisonPill
  }: Receive) orElse behavior

  override def shutdownSettingsGroups(cmd: Http.CloseAll, commanders: Set[ActorRef]): Unit =
    if (!settingsGroups.isEmpty) {
      val running: Set[ActorRef] = settingsGroups.map { x ⇒ x._2 ! cmd; x._2 }(collection.breakOut)
      context.become(closingSettingsGroups(cmd, running, commanders))
    } else shutdownHostConnectors(cmd, commanders)

  override def closingSettingsGroups(cmd: Http.CloseAll, running: Set[ActorRef], commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case _: Http.CloseAll ⇒ context.become(closingSettingsGroups(cmd, running, commanders + sender))

      case Http.ClosedAll ⇒
        val stillRunning = running - sender
        if (stillRunning.isEmpty) shutdownHostConnectors(cmd, commanders)
        else context.become(closingSettingsGroups(cmd, stillRunning, commanders))

      case Terminated(child) if running contains child ⇒ self.tell(Http.ClosedAll, child)
    }

  override def shutdownHostConnectors(cmd: Http.CloseAll, commanders: Set[ActorRef]): Unit =
    if (!hostConnectors.isEmpty) {
      val running: Set[ActorRef] = hostConnectors.map { x ⇒ x._2 ! cmd; x._2 }(collection.breakOut)
      context.become(closingHostConnectors(running, commanders))
    } else shutdownListeners(commanders)

  override def closingHostConnectors(running: Set[ActorRef], commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case cmd: Http.CloseCommand ⇒ context.become(closingHostConnectors(running, commanders + sender))

      case Http.ClosedAll ⇒
        val stillRunning = running - sender
        if (stillRunning.isEmpty) shutdownListeners(commanders)
        else context.become(closingHostConnectors(stillRunning, commanders))

      case Terminated(child) if running contains child ⇒ self.tell(Http.ClosedAll, child)
    }

  override def shutdownListeners(commanders: Set[ActorRef]): Unit = {
    listeners foreach { x ⇒ x ! Http.Unbind }
    context.become(unbinding(listeners.toSet, commanders))
    if (listeners.isEmpty) self ! Http.Unbound
  }

  override def unbinding(running: Set[ActorRef], commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case cmd: Http.CloseCommand ⇒ context.become(unbinding(running, commanders + sender))

      case Http.Unbound ⇒
        val stillRunning = running - sender
        if (stillRunning.isEmpty) {
          commanders foreach (_ ! Http.ClosedAll)
          context.become(receive)
        } else context.become(unbinding(stillRunning, commanders))

      case Terminated(child) if running contains child ⇒ self.tell(Http.Unbound, child)
    }

  override def hostConnectorFor(setup: Http.HostConnectorSetup): ActorRef = {
    val normalizedSetup = setup.normalized

    def createAndRegisterHostConnector = {
      import normalizedSetup._
      val settingsGroup = settingsGroupFor(settings.get.connectionSettings) // must not be moved into the Props(...)!
      val hostConnector = context.actorOf(
          props = Props(new HttpHostConnector(normalizedSetup, settingsGroup)) withDispatcher HostConnectorDispatcher,
          name = "host-connector-" + hostConnectorCounter.next())
      hostConnectors = hostConnectors.updated(normalizedSetup, hostConnector)
      context.watch(hostConnector)
    }
    hostConnectors.getOrElse(normalizedSetup, createAndRegisterHostConnector)
  }

  override def settingsGroupFor(settings: ClientConnectionSettings): ActorRef = {
    def createAndRegisterSettingsGroup = {
      val group = context.actorOf(
        props = Props(new HttpClientSettingsGroup(settings, httpSettings)) withDispatcher SettingsGroupDispatcher,
        name = "group-" + groupCounter.next())
      settingsGroups = settingsGroups.updated(settings, group)
      context.watch(group)
    }
    settingsGroups.getOrElse(settings, createAndRegisterSettingsGroup)
  }
}

class SocketListener(bindCommander: ActorRef,
                     bind: Http.Bind,
                     httpSettings: HttpExt#Settings) extends HttpListener(bindCommander, bind, httpSettings){

  override val pipelineStage = SocketListener.pipelineStage(settings, statsHolder)
}
object SocketListener{

  def pipelineStage(settings: ServerSettings, statsHolder: Option[StatsHolder]) = {
    import settings._

    Switching(
      ServerFrontend(settings) >>
        RequestChunkAggregation(requestChunkAggregationLimit) ? (requestChunkAggregationLimit > 0) >>
        PipeliningLimiter(pipeliningLimit) ? (pipeliningLimit > 0) >>
        StatsSupport(statsHolder.get) ? statsSupport >>
        RemoteAddressHeaderSupport ? remoteAddressHeader >>
        RequestParsing(settings) >>
        ResponseRendering(settings) >>
        ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite),
      (upgrade: Sockets.Upgrade) =>
        WebsocketFrontEnd(upgrade.frameHandler) >>
          AutoPingPongs(upgrade.autoPingInterval, upgrade.pingGenerator) >>
          Consolidation(upgrade.frameSizeLimit) >>
          FrameParsing(upgrade.frameSizeLimit)
    ) >>
      SslTlsSupport ? sslEncryption >>
      TickGenerator(reapingCycle) ? (reapingCycle.isFinite && (idleTimeout.isFinite || requestTimeout.isFinite))
  }

  def pipelineStageOld(settings: ServerSettings, statsHolder: Option[StatsHolder]) = {
    import settings._
    ServerFrontend(settings) >>
      RequestChunkAggregation(requestChunkAggregationLimit) ? (requestChunkAggregationLimit > 0) >>
      PipeliningLimiter(pipeliningLimit) ? (pipeliningLimit > 0) >>
      StatsSupport(statsHolder.get) ? statsSupport >>
      RemoteAddressHeaderSupport ? remoteAddressHeader >>
      RequestParsing(settings) >>
      ResponseRendering(settings) >>
      ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite) >>
      SslTlsSupport ? sslEncryption >>
      TickGenerator(reapingCycle) ? (reapingCycle.isFinite && (idleTimeout.isFinite || requestTimeout.isFinite))
  }

}
case class Switching(stage1: RawPipelineStage[SslTlsContext with ServerFrontend.Context], stage2: Sockets.Upgrade => RawPipelineStage[SslTlsContext with ServerFrontend.Context]) extends RawPipelineStage[SslTlsContext with ServerFrontend.Context] {

  def apply(context: SslTlsContext with ServerFrontend.Context, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      val pl1 = stage1(context, commandPL, eventPL)

      var eventPLVar = pl1.eventPipeline
      var commandPLVar = pl1.commandPipeline

      // it is important to introduce the proxy to the var here
      def commandPipeline: CPL = {
        case Response(_, upgrade: Sockets.Upgrade) =>
          val pl2 = stage2(upgrade)(context, commandPL, eventPL)
          eventPLVar = pl2.eventPipeline
          commandPLVar = pl2.commandPipeline
          eventPLVar(Connected)
        case c => commandPLVar(c)
      }
      def eventPipeline: EPL = {
        c => eventPLVar(c)
      }
    }
}