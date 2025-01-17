// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.internal.health.supervisor

import akka.Done
import akka.actor.{ Actor, ActorContext, ActorRef, ActorSystem, PoisonPill, Props, Terminated }
import akka.pattern.{ ask, BackoffOpts, BackoffSupervisor }
import org.slf4j.{ Logger, LoggerFactory }
import surge.health._
import surge.health.domain.HealthSignal
import surge.health.matchers.SignalPatternMatcher
import surge.internal.config.BackoffConfig
import surge.internal.health._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.languageFeature.postfixOps
import scala.util.Try

trait RegistrationSupport {
  def registrar(): ActorRef
  def register(registration: HealthRegistration): Unit
}

object HealthSignalStreamMonitoringRefWithSupervisionSupport {
  def from(streamMonitoringRef: StreamMonitoringRef): HealthSignalStreamMonitoringRefWithSupervisionSupport = {
    new HealthSignalStreamMonitoringRefWithSupervisionSupport(streamMonitoringRef.actor)
  }
}

class HealthSignalStreamMonitoringRefWithSupervisionSupport(override val actor: ActorRef) extends StreamMonitoringRef(actor) with SupervisionMonitorSupport {

  override def healthSignalReceived(received: HealthSignalReceived): Unit = {
    actor ! received
  }

  override def registrationReceived(received: HealthRegistrationReceived): Unit = {
    actor ! received
  }
}

/**
 * HealthSupervisorActorRef
 * @param actor
 *   ActorRef
 */
class HealthSupervisorActorRef(val actor: ActorRef, askTimeout: FiniteDuration, override val actorSystem: ActorSystem) extends HealthSupervisorTrait {
  private var started: Boolean = false
  private implicit val executionContext: ExecutionContext = actorSystem.dispatcher
  def start(replyTo: Option[ActorRef] = None): HealthSupervisorActorRef = {
    actor ! Start(replyTo)
    started = true
    this
  }

  override def stop(): HealthSupervisorTrait = {
    actor ! Stop
    started = false
    this
  }

  def terminate(): Unit = {
    started = false
    actor ! PoisonPill
  }

  override def state(): HealthSupervisorState = {
    HealthSupervisorState(started)
  }

  override def registrar(): ActorRef = actor

  override def register(registration: HealthRegistration): Future[Any] = {
    actor.ask(registration)(askTimeout)
  }
}

// Commands
case class Start(replyTo: Option[ActorRef] = None)
case class RestartComponent(replyTo: ActorRef)
case class ShutdownComponent(replyTo: ActorRef)
case class HealthRegistrationRequest()
case class Stop()

// State
case class HealthState(registered: Map[String, HealthRegistration] = Map.empty, replyTo: Option[ActorRef] = None)

object HealthSupervisorActor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(signalBus: HealthSignalBusInternal, filters: Seq[SignalPatternMatcher], actorSystem: ActorSystem): HealthSupervisorActorRef = {
    val props = BackoffSupervisor.props(
      BackoffOpts
        .onFailure(
          Props(new HealthSupervisorActor(signalBus, filters)),
          childName = "healthSupervisorActor",
          minBackoff = BackoffConfig.HealthSupervisorActor.minBackoff,
          maxBackoff = BackoffConfig.HealthSupervisorActor.maxBackoff,
          randomFactor = BackoffConfig.HealthSupervisorActor.randomFactor)
        .withMaxNrOfRetries(BackoffConfig.HealthSupervisorActor.maxRetries))
    val actorRef = actorSystem.actorOf(props)
    new HealthSupervisorActorRef(actorRef, 30.seconds, actorSystem)
  }

  protected[supervisor] class ContextForwardingRegistrationHandler(context: ActorContext) extends RegistrationHandler {
    override def handle(registration: HealthRegistration): Try[Done] = Try {
      context.self ! registration
      Done
    }
  }

  protected[supervisor] class ContextForwardingSignalHandler(context: ActorContext) extends SignalHandler {
    override def handle(signal: HealthSignal): Try[Done] = Try {
      context.self ! signal
      Done
    }
  }
}

class HealthSupervisorActor(internalSignalBus: HealthSignalBusInternal, filters: Seq[SignalPatternMatcher])
    extends Actor
    with HealthSignalListener
    with HealthRegistrationListener {
  import HealthSupervisorActor._

  val state: HealthState = HealthState()

  val signalHandler: SignalHandler = new ContextForwardingSignalHandler(context)
  val registrationHandler: RegistrationHandler = new ContextForwardingRegistrationHandler(context)

  override def id(): String = "HealthSuperVisorActor_1"

  override def receive: Receive = {
    case Start(replyTo) =>
      start(maybeSideEffect = Some(() => {
        context.become(monitoring(state.copy(replyTo = replyTo)))
      }))
    case Stop =>
      stop()
  }

  def monitoring(state: HealthState): Receive = {
    case Stop =>
      context.become(receive)
      context.self ! Stop
    case reg: HealthRegistration =>
      state.replyTo.foreach(r => r ! HealthRegistrationReceived(reg))
      context.watch(reg.ref)
      context.become(monitoring(state.copy(registered = state.registered + (reg.name -> reg))))
      sender() ! Ack(success = true, None)
    case HealthRegistrationRequest =>
      sender() ! state.registered.values.toList
    case signal: HealthSignal =>
      state.replyTo.foreach(r => r ! HealthSignalReceived(signal))

      state.registered.values.foreach(registered => {
        registered.restartSignalPatterns.foreach(p => {
          if (p.matcher(signal.name).matches()) {
            registered.ref ! RestartComponent(self)
            state.replyTo.foreach(r => r ! RestartComponentAttempted(registered.name))
          }
        })

        registered.shutdownSignalPatterns.foreach(p => {
          if (p.matcher(signal.name).matches()) {
            registered.ref ! ShutdownComponent(self)
            state.replyTo.foreach(r => r ! ShutdownComponentAttempted(registered.name))
          }
        })
      })
    case term: Terminated =>
      context.unwatch(term.actor)
      val remove: Option[(String, HealthRegistration)] = state.registered.find(t => t._2.ref == term.actor)
      remove match {
        case Some(m) =>
          val nextState = state.copy(registered = state.registered - m._1)
          context.become(monitoring(nextState))
        case None =>
          context.become(monitoring(state))
      }
  }

  override def start(maybeSideEffect: Option[() => Unit]): HealthSignalListener = {
    this.subscribe(signalHandler)
    this.listen(registrationHandler)

    maybeSideEffect.foreach(m => m())
    this
  }

  override def stop(): HealthSignalListener = {
    unsubscribe()
    context.stop(self)
    this
  }

  override def signalBus(): HealthSignalBusInternal = internalSignalBus

  override def subscribe(signalHandler: SignalHandler): HealthSignalListener = {
    super.bindSignalHandler(signalHandler)
    signalBus().subscribe(subscriber = this, signalTopic)
    this
  }

  override def listen(registrationHandler: RegistrationHandler): HealthRegistrationListener = {
    super.bindRegistrationHandler(registrationHandler)
    signalBus().subscribe(subscriber = this, signalBus().registrationTopic())
    this
  }

  override def handleMessage(message: HealthMessage): Unit = {
    message match {
      case reg: HealthRegistration =>
        handleRegistration(reg)
      case sig: HealthSignal =>
        handleSignal(sig)
      case other =>
        log.error(s"Unable to handle message of type $other.getClass()")
    }
  }
}
