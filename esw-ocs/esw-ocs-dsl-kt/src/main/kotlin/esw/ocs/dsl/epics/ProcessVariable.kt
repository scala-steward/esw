package esw.ocs.dsl.epics

import csw.params.core.generics.Key
import csw.params.events.Event
import csw.params.events.ObserveEvent
import csw.params.events.SystemEvent
import esw.ocs.dsl.highlevel.EventServiceDsl
import esw.ocs.dsl.params.Params
import esw.ocs.dsl.params.first
import esw.ocs.dsl.params.invoke

interface Refreshable {
    fun refresh(params: Params = Params(setOf()))
}

class ProcessVariable<T> constructor(
        initial: Event,
        private val key: Key<T>,
        private val eventService: EventServiceDsl
) {
    private val eventKey: String = initial.eventKey().key()
    private var latestEvent: Event = initial
    private val subscribers: Set<Refreshable> = mutableSetOf()

    suspend fun bind(refreshable: Refreshable) {
        subscribers + refreshable
        if (subscribers.size == 1) startSubscription()
    }

    suspend fun set(value: T) {
        val param = key.set(value)
        val oldEvent = latestEvent
        when (oldEvent) {
            is SystemEvent -> latestEvent = oldEvent.add(param)
            is ObserveEvent -> latestEvent = oldEvent.add(param)
        }
        eventService.publishEvent(latestEvent)
    }

    // extract first value from a parameter against provided key from param set
    // if not present, throw an exception
    fun get(): T = (latestEvent.paramType())(key).first

    private suspend fun startSubscription() =
            eventService.onEvent(eventKey) { event ->
                latestEvent = event
                subscribers.forEach { it.refresh() }
            }
}
