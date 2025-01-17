// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.core

case class SerializedMessage(key: String, value: Array[Byte], headers: Map[String, String])
case class SerializedAggregate(value: Array[Byte], headers: Map[String, String])

trait SurgeEventReadFormatting[Event] {
  def readEvent(bytes: Array[Byte]): Event
}

trait SurgeAggregateReadFormatting[Agg] {
  def readState(bytes: Array[Byte]): Option[Agg]
}

@deprecated("Encourages the use of an anti-pattern. Use SurgeEventReadFormatting and SurgeAggregateReadFormatting separately.", "0.5.4")
trait SurgeReadFormatting[Agg, Event] extends SurgeEventReadFormatting[Event] with SurgeAggregateReadFormatting[Agg]

trait SurgeEventWriteFormatting[Event] {
  def writeEvent(evt: Event): SerializedMessage
}

trait SurgeAggregateWriteFormatting[Agg] {
  def writeState(agg: Agg): SerializedAggregate
}

@deprecated("Encourages the use of an anti-pattern. Use SurgeAggregateWriteFormatting and SurgeEventWriteFormatting separately.", "0.5.4")
trait SurgeWriteFormatting[Agg, Event] extends SurgeEventWriteFormatting[Event] with SurgeAggregateWriteFormatting[Agg]

trait SurgeAggregateFormatting[Agg] extends SurgeAggregateReadFormatting[Agg] with SurgeAggregateWriteFormatting[Agg]
trait SurgeEventFormatting[Event] extends SurgeEventReadFormatting[Event] with SurgeEventWriteFormatting[Event]
