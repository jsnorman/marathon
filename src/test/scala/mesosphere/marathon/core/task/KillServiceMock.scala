package mesosphere.marathon
package core.task

import akka.Done
import akka.actor.ActorSystem
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.update.InstanceChangedEventsGenerator
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.task.Task.Id
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.test.{Mockito, SettableClock}

import scala.collection.mutable
import scala.concurrent.Future

/**
  * A Mocked KillService that publishes a TASK_KILLED event for each given task and always works successfully
  */
class KillServiceMock(system: ActorSystem) extends KillService with Mockito {

  var numKilled = 0
  val customStatusUpdates = mutable.Map.empty[Instance.Id, Seq[MarathonEvent]]
  val killed = mutable.Set.empty[Instance.Id]
  val eventsGenerator = InstanceChangedEventsGenerator
  val clock = new SettableClock()

  private def killInstance(instance: Instance, reason: KillReason): Future[Done] = synchronized {
    val id = instance.instanceId
    val updatedInstance = instance.copy(state = instance.state.copy(condition = Condition.Killed, goal = Goal.Decommissioned))
    val events = customStatusUpdates.getOrElse(id, eventsGenerator.events(updatedInstance, task = None, now = clock.now(), previousState = Some(instance.state)))
    events.foreach(system.eventStream.publish)
    numKilled += 1
    killed += id
    Future.successful(Done)
  }

  override def killUnknownTask(taskId: Id, reason: KillReason): Unit = {
    val instance = mock[Instance]
    instance.instanceId returns taskId.instanceId
    killInstance(instance, reason)
  }

  override def killInstancesAndForget(instances: Seq[Instance], reason: KillReason): Unit = {
    instances.foreach { instance =>
      killInstance(instance, reason)
    }
  }
}

