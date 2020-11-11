// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.core

import java.util.UUID

import akka.Done
import akka.actor.{ ActorRef, ActorSystem, NoSerializationVerificationNeeded, Props, ReceiveTimeout }
import akka.pattern._
import akka.serialization.Serializers
import akka.testkit.{ TestKit, TestProbe }
import akka.util.Timeout
import com.ultimatesoftware.scala.core.monitoring.metrics.NoOpMetricsProvider
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{ BeforeAndAfterAll, PartialFunctionValues }
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{ JsValue, Json }
import surge.core.GenericAggregateActor.Stop
import surge.akka.cluster.Passivate
import surge.kafka.streams.{ AggregateStateStoreKafkaStreams, KafkaStreamsKeyValueStore }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class GenericAggregateActorSpec extends TestKit(ActorSystem("GenericAggregateActorSpec")) with AnyWordSpecLike with Matchers
  with BeforeAndAfterAll with MockitoSugar with TestBoundedContext with PartialFunctionValues {
  import TestBoundedContext._

  private implicit val timeout: Timeout = Timeout(10.seconds)
  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def randomUUID: String = UUID.randomUUID().toString

  private def testActor(aggregateId: String = randomUUID, producerActor: KafkaProducerActor[State, BaseTestEvent],
    aggregateKafkaStreamsImpl: AggregateStateStoreKafkaStreams[JsValue]): ActorRef = {

    val props = testActorProps(aggregateId, producerActor, aggregateKafkaStreamsImpl)
    system.actorOf(props)
  }

  private def testActorProps(aggregateId: String = randomUUID, producerActor: KafkaProducerActor[State, BaseTestEvent],
    aggregateKafkaStreamsImpl: AggregateStateStoreKafkaStreams[JsValue]): Props = {
    val metrics = GenericAggregateActor.createMetrics(NoOpMetricsProvider, "testAggregate")

    GenericAggregateActor.props(aggregateId, businessLogic, producerActor, metrics, aggregateKafkaStreamsImpl)
  }

  private def envelope(cmd: BaseTestCommand): GenericAggregateActor.CommandEnvelope[BaseTestCommand] = {
    GenericAggregateActor.CommandEnvelope(cmd.aggregateId, cmd)
  }

  private def mockKafkaStreams(state: State): AggregateStateStoreKafkaStreams[JsValue] = {
    val mockStreams = mock[AggregateStateStoreKafkaStreams[JsValue]]
    when(mockStreams.getAggregateBytes(anyString)).thenReturn(Future.successful(Some(Json.toJson(state).toString().getBytes())))
    when(mockStreams.substatesForAggregate(anyString)(any[ExecutionContext])).thenReturn(Future.successful(List()))

    mockStreams
  }

  private def defaultMockProducer: KafkaProducerActor[State, BaseTestEvent] = {
    val mockProducer = mock[KafkaProducerActor[State, BaseTestEvent]]
    when(mockProducer.isAggregateStateCurrent(anyString)).thenReturn(Future.successful(true))
    when(mockProducer.publish(anyString, any[(String, Option[State])], any[Seq[BaseTestEvent]]))
      .thenReturn(Future.successful(Done))

    mockProducer
  }

  case class Publish(aggregateId: String) extends NoSerializationVerificationNeeded
  private def probeBackedMockProducer(probe: TestProbe): KafkaProducerActor[State, BaseTestEvent] = {
    val mockProducer = mock[KafkaProducerActor[State, BaseTestEvent]]
    when(mockProducer.isAggregateStateCurrent(anyString)).thenReturn(Future.successful(true))
    when(mockProducer.publish(anyString, any[(String, Option[State])], any[Seq[BaseTestEvent]]))
      .thenAnswer((invocation: InvocationOnMock) ⇒ {
        val aggregateId = invocation.getArgument[String](0)
        (probe.ref ? Publish(aggregateId)).map(_ ⇒ Done)(ExecutionContext.global)
      })

    mockProducer
  }

  private def processIncrementCommand(actor: ActorRef, state: State, mockProducer: KafkaProducerActor[State, BaseTestEvent]): Unit = {
    val probe = TestProbe()

    val incrementCmd = Increment(state.aggregateId)
    val testEnvelope = envelope(incrementCmd)

    val expectedEvent = CountIncremented(state.aggregateId, 1, state.version + 1)
    probe.send(actor, testEnvelope)

    val expectedState = BusinessLogic.handleEvent(Some(state), expectedEvent)

    probe.expectMsg(GenericAggregateActor.CommandSuccess(expectedState))

    val expectedStateKeyValues = state.aggregateId -> expectedState

    val expectedEventKeyVal = expectedEvent
    verify(mockProducer).publish(state.aggregateId, expectedStateKeyValues, Seq(expectedEventKeyVal))
  }

  object TestContext {
    def setupDefault: TestContext = {
      setupDefault()
    }

    def setupDefault(
      testAggregateId: String = randomUUID,
      mockProducer: KafkaProducerActor[State, BaseTestEvent] = defaultMockProducer): TestContext = {
      val probe = TestProbe()

      val baseState = State(testAggregateId, 3, 3)
      val mockStreams = mockKafkaStreams(baseState)
      val actor = probe.childActorOf(testActorProps(testAggregateId, mockProducer, mockStreams))

      TestContext(probe, baseState, mockProducer, actor)
    }
  }
  case class TestContext(probe: TestProbe, baseState: State, mockProducer: KafkaProducerActor[State, BaseTestEvent], actor: ActorRef) {
    val testAggregateId: String = baseState.aggregateId
  }

  "GenericAggregateActor" should {
    "Properly initialize from Kafka streams" in {
      val testContext = TestContext.setupDefault
      import testContext._

      probe.send(actor, GenericAggregateActor.GetState(testAggregateId))
      probe.expectMsg(GenericAggregateActor.StateResponse(Some(baseState)))

      processIncrementCommand(actor, baseState, mockProducer)
    }

    "Retry initialization if not up to date" in {
      val probe = TestProbe()

      val testAggregateId = UUID.randomUUID().toString
      val baseState = State(testAggregateId, 3, 3)

      val mockProducer = mock[KafkaProducerActor[State, BaseTestEvent]]
      when(mockProducer.isAggregateStateCurrent(anyString)).thenReturn(Future.successful(false), Future.successful(true))
      when(mockProducer.publish(anyString, any[(String, Option[State])],
        any[Seq[BaseTestEvent]])).thenReturn(Future.successful(Done))

      val mockStreams = mockKafkaStreams(baseState)

      val actor = testActor(testAggregateId, mockProducer, mockStreams)

      probe.send(actor, GenericAggregateActor.GetState(testAggregateId))
      probe.expectMsg(GenericAggregateActor.StateResponse(Some(baseState)))
    }

    "Retry initialization on a failure to read from the KTable" in {
      val probe = TestProbe()

      val testAggregateId = UUID.randomUUID().toString
      val baseState = State(testAggregateId, 3, 3)

      val mockProducer = defaultMockProducer

      val mockStore = mock[KafkaStreamsKeyValueStore[String, Array[Byte]]]
      val mockStreams = mockKafkaStreams(baseState)
      when(mockStore.get(testAggregateId.toString)).thenReturn(
        Future.failed[Option[Array[Byte]]](new RuntimeException("This is expected")),
        Future.successful(Some(Json.toJson(baseState).toString().getBytes())))

      val actor = testActor(testAggregateId, mockProducer, mockStreams)

      probe.send(actor, GenericAggregateActor.GetState(testAggregateId))
      probe.expectMsg(5.seconds, GenericAggregateActor.StateResponse(Some(baseState)))
    }

    "Not update state if there are no events processed" in {
      val testContext = TestContext.setupDefault
      import testContext._

      val testEnvelope = envelope(DoNothing(testAggregateId))

      probe.send(actor, testEnvelope)
      probe.expectMsg(GenericAggregateActor.CommandSuccess(Some(baseState)))

      verify(mockProducer, never()).publish(
        anyString,
        any[(String, Option[State])],
        any[Seq[BaseTestEvent]])
    }

    "Handle exceptions from the domain by returning a CommandError" in {
      val testContext = TestContext.setupDefault
      import testContext._

      val testException = new RuntimeException("This is an expected exception")
      val validationCmd = FailCommandProcessing(testAggregateId, testException)
      val testEnvelope = envelope(validationCmd)

      probe.send(actor, testEnvelope)
      val commandError = probe.expectMsgClass(classOf[GenericAggregateActor.CommandError])
      // Fuzzy matching because serializing and deserializing gets a different object and messes up .equals even though the two are identical
      commandError.exception shouldBe a[RuntimeException]
      commandError.exception.getMessage shouldEqual testException.getMessage
    }

    "Process commands one at a time" in {
      val producerProbe = TestProbe()
      val testContext = TestContext.setupDefault(mockProducer = probeBackedMockProducer(producerProbe))
      import testContext._

      val probe = TestProbe()

      val incrementCmd = Increment(baseState.aggregateId)
      val testEnvelope = envelope(incrementCmd)

      val expectedEvent1 = CountIncremented(baseState.aggregateId, 1, baseState.version + 1)
      val expectedState1 = BusinessLogic.handleEvent(Some(baseState), expectedEvent1)

      val expectedEvent2 = CountIncremented(expectedState1.get.aggregateId, 1, expectedState1.get.version + 1)
      val expectedState2 = BusinessLogic.handleEvent(expectedState1, expectedEvent2)

      probe.send(actor, testEnvelope)
      actor ! ReceiveTimeout // This should be ignored while the actor is processing a command
      probe.send(actor, testEnvelope)

      producerProbe.expectMsg(Publish(testAggregateId))
      producerProbe.reply(Done)
      probe.expectMsg(GenericAggregateActor.CommandSuccess(expectedState1))

      producerProbe.expectMsg(Publish(testAggregateId))
      producerProbe.reply(Done)
      probe.expectMsg(GenericAggregateActor.CommandSuccess(expectedState2))
    }

    "Crash the actor to force reinitialization if publishing events hits an error" in {
      val crashingMockProducer = mock[KafkaProducerActor[State, BaseTestEvent]]
      when(crashingMockProducer.isAggregateStateCurrent(anyString)).thenReturn(Future.successful(true))
      when(crashingMockProducer.publish(anyString, any[(String, Option[State])], any[Seq[BaseTestEvent]]))
        .thenReturn(Future.failed(new RuntimeException("This is expected")))

      val testContext = TestContext.setupDefault(mockProducer = crashingMockProducer)
      import testContext._

      probe.watch(actor)

      val incrementCmd = Increment(baseState.aggregateId)
      val testEnvelope = envelope(incrementCmd)
      probe.send(actor, testEnvelope)
      probe.expectTerminated(actor)
    }

    "Be able to correctly extract the correct aggregate ID from messages" in {
      val command1 = GenericAggregateActor.CommandEnvelope(
        aggregateId = "foobarbaz",
        command = "unused")
      val command2 = GenericAggregateActor.CommandEnvelope(
        aggregateId = randomUUID, command = "unused")

      val getState1 = GenericAggregateActor.GetState(aggregateId = "foobarbaz")
      val getState2 = GenericAggregateActor.GetState(aggregateId = randomUUID)

      GenericAggregateActor.RoutableMessage.extractEntityId(command1) shouldEqual command1.aggregateId
      GenericAggregateActor.RoutableMessage.extractEntityId(command2) shouldEqual command2.aggregateId

      GenericAggregateActor.RoutableMessage.extractEntityId(getState1) shouldEqual getState1.aggregateId
      GenericAggregateActor.RoutableMessage.extractEntityId(getState2) shouldEqual getState2.aggregateId
    }

    "Passivate after the actor idle timeout threshold is exceeded" in {
      val testContext = TestContext.setupDefault
      import testContext._

      probe.send(actor, ReceiveTimeout) // When uninitialized, the actor should ignore a ReceiveTimeout
      probe.expectNoMessage()

      processIncrementCommand(actor, baseState, mockProducer)

      probe.watch(actor)

      actor ! ReceiveTimeout
      probe.expectMsg(Passivate(Stop))
      probe.reply(Stop)

      probe.expectTerminated(actor)
    }

    "Serialize/Deserialize a CommandEnvelope from Akka" in {
      import akka.serialization.SerializationExtension
      def doSerde[A](envelope: GenericAggregateActor.CommandEnvelope[A]): Unit = {
        val serialization = SerializationExtension.get(system)
        val serializer = serialization.findSerializerFor(envelope)
        val serialized = serialization.serialize(envelope).get
        val manifest = Serializers.manifestFor(serializer, envelope)
        val deserialized = serialization.deserialize(serialized, serializer.identifier, manifest).get
        deserialized shouldEqual envelope
      }
      doSerde(GenericAggregateActor.CommandEnvelope[String]("hello", "test2"))
      doSerde(envelope(Increment(UUID.randomUUID().toString)))
    }
  }
}