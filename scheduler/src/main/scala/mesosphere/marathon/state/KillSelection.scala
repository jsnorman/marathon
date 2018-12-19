package mesosphere.marathon
package state

/**
  * Defines a kill selection for tasks. See [[mesosphere.marathon.core.deployment.ScalingProposition]].
  */
sealed trait KillSelection {
  def apply(a: Timestamp, b: Timestamp): Boolean = this match {
    case KillSelection.YoungestFirst => a.youngerThan(b)
    case KillSelection.OldestFirst => a.olderThan(b)
  }
  val value: String

  def toProto: Protos.KillSelection
}

object KillSelection {

  case object YoungestFirst extends KillSelection {
    override val value = "YOUNGEST_FIRST"
    override val toProto: Protos.KillSelection =
      Protos.KillSelection.YoungestFirst
  }
  case object OldestFirst extends KillSelection {
    override val value = "OLDEST_FIRST"
    override val toProto: Protos.KillSelection =
      Protos.KillSelection.OldestFirst
  }

  lazy val DefaultKillSelection: KillSelection = YoungestFirst

  private[this] val proto2Model = Map(
    Protos.KillSelection.YoungestFirst -> YoungestFirst,
    Protos.KillSelection.OldestFirst -> OldestFirst
  )

  def fromProto(proto: Protos.KillSelection): KillSelection = proto2Model(proto)
}