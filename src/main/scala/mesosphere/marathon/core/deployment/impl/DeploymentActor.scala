package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment._
import mesosphere.marathon.core.deployment.impl.DeploymentActor.{Cancel, Fail, NextStep}
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.DeploymentFinished
import mesosphere.marathon.core.event.{AppTerminatedEvent, DeploymentStatus, DeploymentStepFailure, DeploymentStepSuccess}
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.termination.impl.KillStreamWatcher
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, RunSpec}
import mesosphere.mesos.Constraints

import scala.async.Async._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

private class DeploymentActor(
    deploymentManagerActor: ActorRef,
    killService: KillService,
    plan: DeploymentPlan,
    instanceTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor) extends Actor with StrictLogging {

  import context.dispatcher

  val steps = plan.steps.iterator
  var currentStepNr: Int = 0
  implicit val materializer = ActorMaterializer()

  // Default supervision strategy is overridden here to restart deployment child actors (responsible for individual
  // deployment steps e.g. TaskStartActor etc.) even if any exception occurs (even during initialisation).
  // This is due to the fact that child actors tend to gather information during preStart about the tasks that are
  // already running from the TaskTracker and LaunchQueue and those calls can timeout. In general deployment child
  // actors are built idempotent which should make restarting them possible.
  // Additionally a BackOffSupervisor is used to make sure child actor failures are not overloading other parts of the system
  // (like LaunchQueue and InstanceTracker) and are not filling the log with exceptions.
  import akka.pattern.{Backoff, BackoffSupervisor}

  import scala.concurrent.duration._

  def childSupervisor(props: Props, name: String): Props = {
    BackoffSupervisor.props(
      Backoff.onFailure(
        childProps = props,
        childName = name,
        minBackoff = 5.seconds,
        maxBackoff = 1.minute,
        randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
      ).withSupervisorStrategy(
        OneForOneStrategy() {
          case NonFatal(_) => SupervisorStrategy.Restart
          case _ => SupervisorStrategy.Escalate
        }
      ))
  }

  override def preStart(): Unit = {
    self ! NextStep
  }

  def receive: Receive = {
    case NextStep if steps.hasNext =>
      val step = steps.next()
      currentStepNr += 1
      logger.debug(s"Process next deployment step: stepNumber=$currentStepNr step=$step planId=${plan.id}")
      deploymentManagerActor ! DeploymentStepInfo(plan, step, currentStepNr)

      performStep(step) onComplete {
        case Success(_) => self ! NextStep
        case Failure(t) => self ! Fail(t)
      }

    case NextStep =>
      // no more steps, we're done
      logger.debug(s"No more deployment steps to process: planId=${plan.id}")
      deploymentManagerActor ! DeploymentFinished(plan, Success(Done))
      context.stop(self)

    case Cancel(t) =>
      deploymentManagerActor ! DeploymentFinished(plan, Failure(t))
      context.stop(self)

    case Fail(t) =>
      logger.debug(s"Deployment failed: planId=${plan.id}", t)
      deploymentManagerActor ! DeploymentFinished(plan, Failure(t))
      context.stop(self)
  }

  // scalastyle:off
  def performStep(step: DeploymentStep): Future[Done] = {
    logger.debug(s"Perform deployment step: step=$step planId=${plan.id}")
    if (step.actions.isEmpty) {
      Future.successful(Done)
    } else {
      val status = DeploymentStatus(plan, step)
      eventBus.publish(status)

      val futures = step.actions.map { action =>
        action.runSpec match {
          case app: AppDefinition => healthCheckManager.addAllFor(app, Seq.empty)
          case pod: PodDefinition => //ignore: no marathon based health check for pods
        }
        action match {
          case StartApplication(run) => startRunnable(run)
          case ScaleApplication(run, scaleTo, toKill) => scaleRunnable(run, scaleTo, toKill, status)
          case RestartApplication(run) => restartRunnable(run, status)
          case StopApplication(run) => stopRunnable(run.withInstances(0))
        }
      }

      Future.sequence(futures).map(_ => Done).andThen {
        case Success(_) =>
          logger.debug(s"Deployment step successful: step=$step plandId=${plan.id}")
          eventBus.publish(DeploymentStepSuccess(plan, step))
        case Failure(e) =>
          logger.debug(s"Deployment step failed: step=$step plandId=${plan.id}", e)
          eventBus.publish(DeploymentStepFailure(plan, step))
      }
    }
  }

  // scalastyle:on

  def startRunnable(runnableSpec: RunSpec): Future[Done] = {
    logger.info(s"Starting 0 instances of the ${runnableSpec.id} was immediately successful")
    Future.successful(Done)
  }

  private def killInstancesIfNeeded(instancesToKill: Seq[Instance]): Future[Done] = async {
    logger.debug("Kill instances {}", instancesToKill)
    val instancesAreTerminal = KillStreamWatcher.watchForKilledInstances(instanceTracker.instanceUpdates, instancesToKill)
    val changeGoalsFuture = instancesToKill.map(i => {
      if (i.hasReservation) instanceTracker.setGoal(i.instanceId, Goal.Stopped, GoalChangeReason.DeploymentScaling)
      else instanceTracker.setGoal(i.instanceId, Goal.Decommissioned, GoalChangeReason.DeploymentScaling)
    })
    await(Future.sequence(changeGoalsFuture))
    await(instancesAreTerminal)
  }

  def scaleRunnable(runnableSpec: RunSpec, scaleTo: Int,
    toKill: Option[Seq[Instance]],
    status: DeploymentStatus): Future[Done] = {
    logger.debug(s"Scale runnable $runnableSpec")

    def killToMeetConstraints(notSentencedAndRunning: Seq[Instance], toKillCount: Int) = {
      Constraints.selectInstancesToKill(runnableSpec, notSentencedAndRunning, toKillCount)
    }

    async {
      val instances = await(instanceTracker.specInstances(runnableSpec.id))
      val runningInstances = instances.filter(_.state.condition.isActive)
      val ScalingProposition(instancesToKill, tasksToStart) = ScalingProposition.propose(
        runningInstances, toKill, killToMeetConstraints, scaleTo, runnableSpec.killSelection)

      logger.debug("Kill tasks if needed")
      await(instancesToKill.fold(Future.successful(Done))(ik => killInstancesIfNeeded(ik).map(_ => Done)))

      def startInstancesIfNeeded: Future[Done] = {
        tasksToStart.fold(Future.successful(Done)) { tasksToStart =>
          logger.debug(s"Start next $tasksToStart tasks")
          val promise = Promise[Unit]()
          context.actorOf(childSupervisor(TaskStartActor.props(deploymentManagerActor, status, launchQueue, instanceTracker, eventBus,
            readinessCheckExecutor, runnableSpec, scaleTo, promise), s"TaskStart-${plan.id}"))
          promise.future.map(_ => Done)
        }
      }
      await(startInstancesIfNeeded)
    }
  }

  def stopRunnable(runSpec: RunSpec): Future[Done] = async {
    logger.debug(s"Stop runnable $runSpec")
    healthCheckManager.removeAllFor(runSpec.id)

    // Purging launch queue
    await(launchQueue.purge(runSpec.id))

    val instances = await(instanceTracker.specInstances(runSpec.id))

    logger.info(s"Killing all instances of ${runSpec.id}: ${instances.map(_.instanceId)}")
    val instancesAreTerminal = KillStreamWatcher.watchForDecomissionedInstances(instanceTracker.instanceUpdates, instances.map(_.instanceId)(collection.breakOut))
    await(Future.sequence(instances.map(i => instanceTracker.setGoal(i.instanceId, Goal.Decommissioned, GoalChangeReason.DeletingApp))))
    await(instancesAreTerminal)

    launchQueue.resetDelay(runSpec)

    // The tasks will be removed from the InstanceTracker when their termination
    // was confirmed by Mesos via a task update.
    eventBus.publish(AppTerminatedEvent(runSpec.id))

    Done
  }.recover {
    case NonFatal(error) =>
      logger.warn(s"Error in stopping runSpec ${runSpec.id}", error)
      Done
  }

  def restartRunnable(run: RunSpec, status: DeploymentStatus): Future[Done] = {
    if (run.instances == 0) {
      Future.successful(Done)
    } else {
      val promise = Promise[Unit]()
      context.actorOf(childSupervisor(TaskReplaceActor.props(deploymentManagerActor, status,
        launchQueue, instanceTracker, eventBus, readinessCheckExecutor, run, promise), s"TaskReplace-${plan.id}"))
      promise.future.map(_ => Done)
    }
  }
}

object DeploymentActor {
  case object NextStep
  case object Finished
  case class Cancel(reason: Throwable)
  case class Fail(reason: Throwable)
  case class DeploymentActionInfo(plan: DeploymentPlan, step: DeploymentStep, action: DeploymentAction)

  def props(
    deploymentManagerActor: ActorRef,
    killService: KillService,
    plan: DeploymentPlan,
    taskTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor): Props = {

    Props(new DeploymentActor(
      deploymentManagerActor,
      killService,
      plan,
      taskTracker,
      launchQueue,
      healthCheckManager,
      eventBus,
      readinessCheckExecutor
    ))
  }
}
