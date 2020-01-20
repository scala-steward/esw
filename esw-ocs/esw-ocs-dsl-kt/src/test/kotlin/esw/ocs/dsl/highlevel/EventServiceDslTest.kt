package esw.ocs.dsl.highlevel

import akka.Done.done
import akka.actor.Cancellable
import csw.event.api.javadsl.IEventPublisher
import csw.event.api.javadsl.IEventSubscriber
import csw.event.api.javadsl.IEventSubscription
import csw.params.events.*
import esw.ocs.dsl.highlevel.models.EventSubscription
import esw.ocs.dsl.highlevel.models.Prefix
import io.kotlintest.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.milliseconds

class EventServiceDslTest : EventServiceDsl {

    private val key = "TCS.test.eventkey1"
    private val duration = 10.milliseconds

    private val event: Event = mockk<ObserveEvent>()
    private val eventKeys = HashSet<EventKey>()
    private val eventSet = HashSet<Event>(1)

    private val eventSubscription = mockk<IEventSubscription>()
    private val cancellable = mockk<Cancellable>()
    private val eventCallback = mockk<(Event) -> CompletableFuture<*>>()

    override val coroutineScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext)
    override val eventPublisher: IEventPublisher = mockk()
    override val eventSubscriber: IEventSubscriber = mockk()

    init {
        eventKeys.add(EventKey.apply(key))
        eventSet.add(event)
    }

    @Test
    fun `systemEvent should return a SystemEvent created with given parameters | ESW-120`() {
        val eventName = "systemEvent1"
        val eventPrefix = "TCS.filter.wheel"
        val actualEvent = SystemEvent(eventPrefix, eventName)

        // Verify that  event with provided prefix and eventName is created.
        actualEvent shouldBe SystemEvent(
                actualEvent.eventId(),
                Prefix(eventPrefix),
                EventName(eventName),
                actualEvent.eventTime(),
                actualEvent.paramSet()
        )
    }

    @Test
    fun `observeEvent should return a ObserveEvent created with given parameters | ESW-120`() {
        val eventName = "observeEvent1"
        val eventPrefix = "TCS.filter.wheel"
        val actualEvent = ObserveEvent(eventPrefix, eventName)

        // Verify that event with provided prefix and eventName is created.
        actualEvent shouldBe ObserveEvent(
                actualEvent.eventId(),
                Prefix(eventPrefix),
                EventName(eventName),
                actualEvent.eventTime(),
                actualEvent.paramSet()
        )
    }

    @Test
    fun `publish should delegate to publisher#publish | ESW-120`() = runBlocking {
        every { (this@EventServiceDslTest.eventPublisher.publish(event)) }.returns(CompletableFuture.completedFuture(done()))
        publishEvent(event) shouldBe done()
        verify { this@EventServiceDslTest.eventPublisher.publish(event) }
    }

    @Test
    fun `publishEvent should delegate to publisher#publishAsync | ESW-120`() {
        every { this@EventServiceDslTest.eventPublisher.publishAsync(any(), any()) }.answers { cancellable }
        publishEvent(duration) { event } shouldBe cancellable
        verify { this@EventServiceDslTest.eventPublisher.publishAsync(any(), any()) }
    }

    @Test
    fun `onEvent should delegate to subscriber#subscribeAsync | ESW-120`() = runBlocking {
        every { this@EventServiceDslTest.eventSubscriber.subscribeAsync(eventKeys, any()) }.answers { eventSubscription }
        every { eventSubscription.ready() }.answers { CompletableFuture.completedFuture(done()) }

        onEvent(key) { eventCallback }
        verify { this@EventServiceDslTest.eventSubscriber.subscribeAsync(eventKeys, any()) }
    }

    @Test
    fun `cancel should delegate to IEventSubscription#unsubscribe() | ESW-120`() = runBlocking {
        every { this@EventServiceDslTest.eventSubscriber.subscribeAsync(eventKeys, any()) }.answers { eventSubscription }
        every { eventSubscription.unsubscribe() }.answers { CompletableFuture.completedFuture(done()) }
        every { eventSubscription.ready() }.answers { CompletableFuture.completedFuture(done()) }

        val subscription: EventSubscription = onEvent(key) { eventCallback }
        verify { this@EventServiceDslTest.eventSubscriber.subscribeAsync(eventKeys, any()) }

        subscription.cancel()
        verify { eventSubscription.unsubscribe() }
    }

    @Test
    fun `getEvent should delegate to subscriber#get | ESW-120`() = runBlocking {
        every { this@EventServiceDslTest.eventSubscriber.get(eventKeys) }.answers { CompletableFuture.completedFuture(eventSet) }
        getEvent(key) shouldBe eventSet
        verify { this@EventServiceDslTest.eventSubscriber.get(eventKeys) }
    }
}