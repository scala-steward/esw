package esw.ocs.dsl.highlevel

import akka.Done.done
import csw.alarm.api.javadsl.IAlarmService
import csw.alarm.api.javadsl.JAlarmSeverity.Major
import csw.alarm.models.Key.AlarmKey
import io.kotlintest.eventually
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture.completedFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.seconds
import io.kotlintest.seconds as testSeconds

class AlarmServiceDslTest : AlarmServiceDsl {

    override val alarmService: IAlarmService = mockk()
    override val _alarmRefreshDuration: Duration = 4.seconds
    override val coroutineScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext)

    @Test
    fun `AlarmServiceDsl should set severity of alarms and refresh it | ESW-125`() {
        val alarmKey1 = AlarmKey(Prefix(TCS, "filter_assembly1"), "temperature1")
        val alarmKey2 = AlarmKey(Prefix(TCS, "filter_assembly2"), "temperature2")
        val alarmKey3 = AlarmKey(Prefix(TCS, "filter_assembly3"), "temperature3")

        val severity = Major()

        every { alarmService.setSeverity(alarmKey1, severity) } answers { completedFuture(done()) }
        every { alarmService.setSeverity(alarmKey2, severity) } answers { completedFuture(done()) }
        every { alarmService.setSeverity(alarmKey3, severity) } answers { completedFuture(done()) }

        setSeverity(alarmKey1, severity)
        setSeverity(alarmKey2, severity)
        setSeverity(alarmKey3, severity)

        eventually(5.testSeconds) {
            verify { alarmService.setSeverity(alarmKey1, severity) }
            verify { alarmService.setSeverity(alarmKey2, severity) }
            verify { alarmService.setSeverity(alarmKey3, severity) }
        }

        // to test on refresh functionality
        clearMocks(alarmService)
        every { alarmService.setSeverity(alarmKey1, severity) } answers { completedFuture(done()) }
        every { alarmService.setSeverity(alarmKey2, severity) } answers { completedFuture(done()) }
        every { alarmService.setSeverity(alarmKey3, severity) } answers { completedFuture(done()) }

        eventually(5.testSeconds) {
            verify { alarmService.setSeverity(alarmKey1, severity) }
            verify { alarmService.setSeverity(alarmKey2, severity) }
            verify { alarmService.setSeverity(alarmKey3, severity) }
        }
    }
}
