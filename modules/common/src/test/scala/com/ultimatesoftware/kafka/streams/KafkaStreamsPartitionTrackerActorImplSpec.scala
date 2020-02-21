// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.kafka.streams

import akka.Done
import akka.actor.ActorSystem
import akka.testkit.{ TestKit, TestProbe }
import com.ultimatesoftware.kafka.KafkaConsumerStateTrackingActor
import com.ultimatesoftware.scala.core.kafka.HostPort
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.state.{ HostInfo, StreamsMetadata }
import org.mockito.Mockito._
import org.scalatest.{ Matchers, WordSpecLike }
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.JavaConverters._

class KafkaStreamsPartitionTrackerActorImplSpec extends TestKit(ActorSystem("KafkaStreamsPartitionTrackerActorImplSpec")) with WordSpecLike
  with Matchers with MockitoSugar {

  private val tp0 = new TopicPartition("testTopic", 0)
  private val tp1 = new TopicPartition("testTopic", 1)
  private val tp2 = new TopicPartition("testTopic", 2)
  private val tp3 = new TopicPartition("testTopic", 3)

  "KafkaStreamsPartitionTrackerActorImpl" should {
    "Be able to follow and update partition assignments" in {
      val probe = TestProbe()
      val hostPort1 = HostPort("host1", 1)
      val hostPort2 = HostPort("host2", 2)
      val assignedPartitions1 = List(tp1, tp2, tp3)
      val assignedPartitions2 = List(tp0)

      val partitionAssignments = Map(hostPort1 -> assignedPartitions1, hostPort2 -> assignedPartitions2)

      val testStreamsMeta = List(
        new StreamsMetadata(new HostInfo(hostPort1.host, hostPort1.port), Set("store1", "store2").asJava, assignedPartitions1.toSet.asJava),
        new StreamsMetadata(new HostInfo(hostPort2.host, hostPort2.port), Set("store1").asJava, assignedPartitions2.toSet.asJava))

      val mockStreams = mock[KafkaStreams]
      when(mockStreams.allMetadata()).thenReturn(testStreamsMeta.asJava)

      val trackerImpl = new KafkaStreamsPartitionTrackerActorProvider(probe.ref).create(mockStreams)
      trackerImpl.update()
      probe.expectMsg(KafkaConsumerStateTrackingActor.StateUpdated(partitionAssignments))
      probe.reply(Done)
    }
  }
}
