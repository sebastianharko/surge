// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.akka.streams.kafka

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{ Committer, Consumer }
import akka.kafka.{ CommitterSettings, ConsumerMessage, ConsumerSettings, Subscriptions }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Source }
import com.typesafe.config.{ Config, ConfigFactory }
import com.ultimatesoftware.scala.core.kafka.KafkaTopic
import org.apache.kafka.common.serialization.{ ByteArrayDeserializer, StringDeserializer }

import scala.concurrent.{ ExecutionContext, Future }

trait KafkaConsumerTrait {
  def actorSystem: ActorSystem

  private def defaultCommitterSettings = CommitterSettings(actorSystem)

  private val defaultParallelism = 10

  def createCommittableSource(topic: KafkaTopic, parallelism: Int = defaultParallelism,
    consumerSettings: ConsumerSettings[String, Array[Byte]]): Source[ConsumerMessage.CommittableMessage[String, Array[Byte]], Consumer.Control] = {
    Consumer.committableSource(consumerSettings, Subscriptions.topics(topic.name))
  }

  def commitOffsetSinkAndRun(
    source: Source[ConsumerMessage.Committable, Consumer.Control],
    committerSettings: CommitterSettings = defaultCommitterSettings)(implicit materializer: Materializer): DrainingControl[Done] = {
    source
      .toMat(Committer.sink(committerSettings))(Keep.both)
      .mapMaterializedValue(DrainingControl.apply)
      .run()
  }

  def streamAndCommitOffsets(
    topic: KafkaTopic,
    business: (String, Array[Byte]) ⇒ Future[Done],
    parallelism: Int = defaultParallelism,
    consumerSettings: ConsumerSettings[String, Array[Byte]],
    committerSettings: CommitterSettings = defaultCommitterSettings)(implicit mat: Materializer, ec: ExecutionContext): Consumer.DrainingControl[Done] = {
    val source = createCommittableSource(topic, parallelism, consumerSettings)

    val flow = source.mapAsync(parallelism) { msg ⇒
      business(msg.record.key, msg.record.value).map(_ ⇒ msg.committableOffset)
    }

    commitOffsetSinkAndRun(flow, committerSettings)
  }

}

case class KafkaConsumer()(implicit val actorSystem: ActorSystem) extends KafkaConsumerTrait
object KafkaConsumer {
  private val config: Config = ConfigFactory.load()
  def consumerSettings(actorSystem: ActorSystem, groupId: String): ConsumerSettings[String, Array[Byte]] = {
    val baseSettings = ConsumerSettings[String, Array[Byte]](actorSystem, Some(new StringDeserializer()), Some(new ByteArrayDeserializer()))

    val brokers = config.getString("kafka.brokers")
    baseSettings
      .withBootstrapServers(brokers)
      .withGroupId(groupId)
  }
}
