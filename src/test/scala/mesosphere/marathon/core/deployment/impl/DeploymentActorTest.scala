package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor.ActorRef
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment._
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.DeploymentFinished
import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.update.InstanceChangedEventsGenerator
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.KillServiceMock
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import org.mockito.Matchers
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

// TODO: this is NOT a unit test. the DeploymentActor create child actors that cannot be mocked in the current
// setup which makes the test overly complicated because events etc have to be mocked for these.
// The way forward should be to provide factories that create the child actors with a given context, or
// to use delegates that hide the implementation behind a mockable function call.
class DeploymentActorTest extends AkkaUnitTest with GroupCreation {

  implicit val defaultTimeout: Timeout = 5.seconds

  class Fixture {
    val tracker: InstanceTracker = mock[InstanceTracker]
    tracker.setGoal(any, any, any).returns(Future.successful(Done))
    tracker.instanceUpdates returns Source.empty

    val queue: LaunchQueue = mock[LaunchQueue]
    val killService = new KillServiceMock(system)
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val config: DeploymentConfig = mock[DeploymentConfig]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    config.killBatchSize returns 100
    config.killBatchCycle returns 10.seconds

    tracker.setGoal(any, any, any) answers { args =>
      def sendKilled(instance: Instance, goal: Goal): Unit = {
        val updatedInstance = instance.copy(state = instance.state.copy(condition = Condition.Killed, goal = goal))
        val events = InstanceChangedEventsGenerator.events(updatedInstance, None, Timestamp(0), Some(instance.state))
        events.foreach(system.eventStream.publish)
      }

      val instanceId = args(0).asInstanceOf[Instance.Id]
      val maybeInstance = tracker.get(instanceId).futureValue
      maybeInstance.map { instance =>
        val goal = args(1).asInstanceOf[Goal]
        sendKilled(instance, goal)
        Future.successful(Done)
      }.getOrElse {
        Future.failed(throw new IllegalArgumentException(s"instance $instanceId is not ready in instance tracker when querying"))
      }
    }

    def instanceChanged(app: AppDefinition, condition: Condition): InstanceChanged = {
      val instanceId = Instance.Id.forRunSpec(app.id)
      val instance: Instance = mock[Instance]
      instance.instanceId returns instanceId
      InstanceChanged(instanceId, app.version, app.id, condition, instance)
    }

    def deploymentActor(manager: ActorRef, plan: DeploymentPlan) = system.actorOf(
      DeploymentActor.props(
        manager,
        killService,
        plan,
        tracker,
        queue,
        hcManager,
        system.eventStream,
        readinessCheckExecutor
      )
    )

  }

  "DeploymentActor" should {
    "Deploy" in new Fixture {
      val managerProbe = TestProbe()
      val app1 = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 2)
      val app2 = AppDefinition(id = PathId("/foo/app2"), cmd = Some("cmd"), instances = 1)
      val app3 = AppDefinition(id = PathId("/foo/app3"), cmd = Some("cmd"), instances = 1)
      val app4 = AppDefinition(id = PathId("/foo/app4"), cmd = Some("cmd"))
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(
        app1.id -> app1,
        app2.id -> app2,
        app4.id -> app4))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val app1New = app1.copy(instances = 1, versionInfo = version2)
      val app2New = app2.copy(instances = 2, cmd = Some("otherCmd"), versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(
        app1New.id -> app1New,
        app2New.id -> app2New,
        app3.id -> app3))))

      // setting started at to 0 to make sure this survives
      val instance1_1 = {
        val instance = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }
      val instance1_2 = {
        val instance = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }
      val instance2_1 = {
        val instance = TestInstanceBuilder.newBuilder(app2.id, version = app2.version).addTaskRunning().getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }
      val instance3_1 = {
        val instance = TestInstanceBuilder.newBuilder(app3.id, version = app3.version).addTaskRunning().getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }
      val instance4_1 = {
        val instance = TestInstanceBuilder.newBuilder(app4.id, version = app4.version).addTaskRunning().getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }

      val plan = DeploymentPlan(origGroup, targetGroup)

      queue.purge(any) returns Future.successful(Done)
      tracker.specInstances(Matchers.eq(app1.id))(any[ExecutionContext]) returns Future.successful(Seq(instance1_1, instance1_2))
      tracker.specInstancesSync(app2.id) returns Seq(instance2_1)
      tracker.specInstances(Matchers.eq(app2.id))(any[ExecutionContext]) returns Future.successful(Seq(instance2_1))
      tracker.specInstances(Matchers.eq(app3.id))(any[ExecutionContext]) returns Future.successful(Seq(instance3_1))
      tracker.specInstances(Matchers.eq(app4.id))(any[ExecutionContext]) returns Future.successful(Seq(instance4_1))
      tracker.get(instance1_1.instanceId) returns Future.successful(Some(instance1_1))
      tracker.get(instance1_2.instanceId) returns Future.successful(Some(instance1_2))
      tracker.get(instance2_1.instanceId) returns Future.successful(Some(instance2_1))
      tracker.get(instance3_1.instanceId) returns Future.successful(Some(instance3_1))
      tracker.get(instance4_1.instanceId) returns Future.successful(Some(instance4_1))

      when(queue.add(same(app2New), any[Int])).thenAnswer(new Answer[Future[Done]] {
        def answer(invocation: InvocationOnMock): Future[Done] = {
          for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
            system.eventStream.publish(instanceChanged(app2New, Condition.Running))
          Future.successful(Done)
        }
      })

      deploymentActor(managerProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(7.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))

      verify(tracker).setGoal(instance4_1.instanceId, Goal.Decommissioned, GoalChangeReason.DeletingApp)
      verify(tracker).setGoal(instance1_2.instanceId, Goal.Decommissioned, GoalChangeReason.DeploymentScaling)
      verify(tracker).setGoal(instance2_1.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)
      verify(queue).resetDelay(app4.copy(instances = 0))
    }

    "Restart app" in new Fixture {
      val managerProbe = TestProbe()
      val app = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 2)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(appNew.id -> appNew))))

      val instance1_1 = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
      val instance1_2 = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()

      tracker.specInstancesSync(app.id) returns Seq(instance1_1, instance1_2)
      tracker.get(instance1_1.instanceId) returns Future.successful(Some(instance1_1))
      tracker.get(instance1_2.instanceId) returns Future.successful(Some(instance1_2))
      tracker.specInstances(app.id) returns Future.successful(Seq(instance1_1, instance1_2))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

      tracker.list(appNew.id) returns Future.successful(Seq(instance1_1, instance1_2))

      when(queue.add(same(appNew), any[Int])).thenAnswer(new Answer[Future[Done]] {
        def answer(invocation: InvocationOnMock): Future[Done] = {
          for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
            system.eventStream.publish(instanceChanged(appNew, Condition.Running))
          Future.successful(Done)
        }
      })

      deploymentActor(managerProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }
      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))

      verify(tracker).setGoal(instance1_1.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)
      verify(tracker).setGoal(instance1_2.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)
      verify(queue).add(appNew, 2)
    }

    "Restart suspended app" in new Fixture {
      val managerProbe = TestProbe()

      val app = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 0)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)
      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(appNew.id -> appNew))))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

      tracker.specInstancesSync(app.id) returns Seq.empty[Instance]
      queue.add(app, 2) returns Future.successful(Done)

      deploymentActor(managerProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }
      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))
    }

    "Scale with tasksToKill" in new Fixture {
      val managerProbe = TestProbe()
      val app1 = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 3)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app1.id -> app1))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val app1New = app1.copy(instances = 2, versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app1New.id -> app1New))))

      val instance1_1 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
      val instance1_2 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(500)).getInstance()
      val instance1_3 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()

      val plan = DeploymentPlan(original = origGroup, target = targetGroup, toKill = Map(app1.id -> Seq(instance1_2)))

      tracker.specInstances(Matchers.eq(app1.id))(any[ExecutionContext]) returns Future.successful(Seq(instance1_1, instance1_2, instance1_3))
      tracker.get(instance1_1.instanceId).returns(Future.successful(Some(instance1_1)))
      tracker.get(instance1_2.instanceId).returns(Future.successful(Some(instance1_2)))
      tracker.get(instance1_3.instanceId).returns(Future.successful(Some(instance1_3)))

      deploymentActor(managerProbe.ref, plan)

      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))

      verify(tracker, once).setGoal(any, any, any)
      verify(tracker).setGoal(instance1_2.instanceId, Goal.Decommissioned, GoalChangeReason.DeploymentScaling)
    }
  }
}
