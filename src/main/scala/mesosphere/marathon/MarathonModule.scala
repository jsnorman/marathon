package mesosphere.marathon

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.event.EventStream
import akka.routing.RoundRobinPool
import akka.stream.Materializer
import com.google.inject._
import com.typesafe.scalalogging.StrictLogging
import javax.inject.Named
import mesosphere.marathon.core.base.{CrashStrategy, JvmExitsCrashStrategy}
import mesosphere.marathon.core.deployment.DeploymentManager
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.{DeploymentRepository, GroupRepository}
import mesosphere.util.state._

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

object ModuleNames {
  final val HOST_PORT = "HOST_PORT"

  final val HISTORY_ACTOR_PROPS = "HISTORY_ACTOR_PROPS"

  final val MESOS_HEARTBEAT_ACTOR = "MesosHeartbeatActor"
}

class MarathonModule(conf: MarathonConf, http: HttpConf, actorSystem: ActorSystem)
  extends AbstractModule with StrictLogging {

  def configure(): Unit = {
    bind(classOf[CrashStrategy]).toInstance(JvmExitsCrashStrategy)
    bind(classOf[MarathonConf]).toInstance(conf)
    bind(classOf[DeprecatedFeatureSet]).toInstance(conf.deprecatedFeatures())
    bind(classOf[HttpConf]).toInstance(http)
    bind(classOf[LeaderProxyConf]).toInstance(conf)
    bind(classOf[ZookeeperConf]).toInstance(conf)

    bind(classOf[MarathonSchedulerDriverHolder]).in(Scopes.SINGLETON)
    bind(classOf[SchedulerDriverFactory]).to(classOf[MesosSchedulerDriverFactory]).in(Scopes.SINGLETON)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[DeploymentService]).to(classOf[MarathonSchedulerService])
  }

  @Named(ModuleNames.MESOS_HEARTBEAT_ACTOR)
  @Provides
  @Singleton
  def provideMesosHeartbeatActor(system: ActorSystem): ActorRef = {
    system.actorOf(Heartbeat.props(Heartbeat.Config(
      FiniteDuration(conf.mesosHeartbeatInterval(), TimeUnit.MILLISECONDS),
      conf.mesosHeartbeatFailureThreshold()
    )), ModuleNames.MESOS_HEARTBEAT_ACTOR)
  }

  @Provides
  @Singleton
  def provideMesosLeaderInfo(): MesosLeaderInfo = {
    conf.mesosLeaderUiUrl.toOption match {
      case someUrl @ Some(_) => ConstMesosLeaderInfo(someUrl)
      case None => new MutableMesosLeaderInfo
    }
  }

  @Named("schedulerActor")
  @Provides
  @Singleton
  @Inject
  def provideSchedulerActor(
                             system: ActorSystem,
                             groupRepository: GroupRepository,
                             deploymentRepository: DeploymentRepository,
                             healthCheckManager: HealthCheckManager,
                             killService: KillService,
                             launchQueue: LaunchQueue,
                             driverHolder: MarathonSchedulerDriverHolder,
                             electionService: ElectionService,
                             eventBus: EventStream,
                             deploymentManager: DeploymentManager,
                             instanceTracker: InstanceTracker,
                             @Named(ModuleNames.HISTORY_ACTOR_PROPS) historyActorProps: Props)(implicit mat: Materializer): ActorRef = {
    val supervision = OneForOneStrategy() {
      case NonFatal(_) => Restart
    }

    system.actorOf(
      MarathonSchedulerActor.props(
        groupRepository,
        deploymentManager,
        deploymentRepository,
        historyActorProps,
        healthCheckManager,
        killService,
        launchQueue,
        driverHolder,
        electionService.leadershipTransitionEvents,
        eventBus,
        instanceTracker,
      )(mat).withRouter(RoundRobinPool(nrOfInstances = 1, supervisorStrategy = supervision)),
      "MarathonScheduler")
  }

  @Named(ModuleNames.HOST_PORT)
  @Provides
  @Singleton
  def provideHostPort: String = {
    val port = if (http.disableHttp()) http.httpsPort() else http.httpPort()
    "%s:%d".format(conf.hostname(), port)
  }

  @Provides
  @Singleton
  def provideActorSystem(): ActorSystem = actorSystem

  @Provides
  @Singleton
  def provideActorScheduler(): Scheduler = actorSystem.scheduler

  /* Reexports the `akka.actor.ActorSystem` as `akka.actor.ActorRefFactory`. It doesn't work automatically. */
  @Provides
  @Singleton
  def provideActorRefFactory(system: ActorSystem): ActorRefFactory = system

  @Provides
  @Singleton
  def provideEventBus(actorSystem: ActorSystem): EventStream = actorSystem.eventStream
}
