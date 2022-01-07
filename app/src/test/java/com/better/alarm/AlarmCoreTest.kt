package com.better.alarm

import ch.qos.logback.core.OutputStreamAppender
import com.better.alarm.configuration.Prefs
import com.better.alarm.configuration.Store
import com.better.alarm.interfaces.Intents
import com.better.alarm.logger.Logger
import com.better.alarm.logger.addAppender
import com.better.alarm.logger.logback
import com.better.alarm.logger.patternLayoutEncoder
import com.better.alarm.model.AlarmCore
import com.better.alarm.model.AlarmsScheduler
import com.better.alarm.model.Calendars
import com.better.alarm.model.DaysOfWeek
import com.better.alarm.stores.InMemoryRxDataStoreFactory
import com.better.alarm.util.Optional
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Single
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import java.util.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class AlarmCoreTest {
  companion object {
    @BeforeClass
    @JvmStatic
    fun initLoggers() {
      logback {
        addAppender(OutputStreamAppender()) {
          outputStream = System.out
          encoder = patternLayoutEncoder("[%thread] %-5level %logger{36} - %msg%n")
        }
      }
    }
  }
  private var stateNotifierMock: AlarmCore.IStateNotifier = mockk(relaxed = true)
  private val alarmSetterMock = AlarmSchedulerTest.SetterMock()
  private var testScheduler: TestScheduler = TestScheduler()
  private var store: Store =
      Store(
          alarmsSubject = BehaviorSubject.createDefault(ArrayList()),
          next = BehaviorSubject.createDefault<Optional<Store.Next>>(Optional.absent()),
          sets = PublishSubject.create(),
          events = PublishSubject.create())
  private var prefs: Prefs =
      Prefs.create(Single.just(true), InMemoryRxDataStoreFactory.create()).apply {
        autoSilence.value = 7
        skipDuration.value = 120
      }
  private var logger: Logger = Logger.create()
  private var currentHour = 0
  private var currentMinute = 5
  private var currentDay = 1
  private val calendars = Calendars {
    val instance = Calendar.getInstance()
    instance.set(Calendar.YEAR, 2019)
    instance.set(Calendar.DAY_OF_YEAR, currentDay)
    instance.set(Calendar.HOUR_OF_DAY, currentHour)
    instance.set(Calendar.MINUTE, currentMinute)
    instance.set(Calendar.SECOND, 0)
    instance
  }
  private val containerFactory = TestContainerFactory(calendars)

  fun advanceTime(timeString: String) {
    require(timeString.contains(":"))
    currentHour = timeString.substringBefore(":").toInt()
    currentMinute = timeString.substringAfter(":").toInt()
  }

  @JvmField
  @Rule
  val watcher: TestRule =
      object : TestWatcher() {
        override fun starting(description: Description) {
          println("---- " + description.methodName + " ----")
        }
      }

  fun act(what: String, func: () -> Unit) {
    println("When $what")
    func()
    testScheduler.triggerActions()
  }

  fun createAlarm(): AlarmCore {
    val alarmsScheduler = AlarmsScheduler(alarmSetterMock, logger, store, prefs, calendars)
    alarmsScheduler.start()
    return AlarmCore(
            containerFactory.create(),
            logger,
            alarmsScheduler,
            stateNotifierMock,
            prefs,
            store,
            calendars)
        .apply {
          start()
          testScheduler.triggerActions()
        }
  }

  @Test
  fun `skip notification must be shown if less than 2 hours`() {
    val alarm = createAlarm()

    act("Enable on 01:00") { alarm.edit { copy(isEnabled = true, hour = 1) } }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
  }

  @Test
  fun `skip notification must be not shown if more than 2 hours`() {
    val alarm = createAlarm()
    act("Enable on 03:00") { alarm.edit { copy(isEnabled = true, hour = 3) } }

    verify(exactly = 0) { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
  }

  @Test
  fun `skip notification must be shown if more than 2 hours and time has passed`() {
    val alarm = createAlarm()
    act("Enable on 03:00") { alarm.edit { copy(isEnabled = true, hour = 3) } }

    verify(exactly = 0) { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }

    act("Current time changes to on 02:00") {
      currentHour = 2
      alarm.onInexactAlarmFired()
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
  }

  @Test
  fun `skip notification must be removed if not used and alarm has actually fired`() {
    val alarm = createAlarm()
    act("Set on 01:00") { alarm.edit { copy(isEnabled = true, hour = 1) } }
    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }

    act("Current time changes to on 03:00") {
      currentHour = 3
      alarm.onAlarmFired()
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_REMOVE_SKIP) }
  }

  @Test
  fun `skip notification must be removed if not used and pre alarm has actually fired`() {
    val alarm = createAlarm()
    act("Set on 01:30") {
      alarm.edit {
        copy(isEnabled = true, daysOfWeek = DaysOfWeek(0x7f), hour = 1, minutes = 30)
            .withIsPrealarm(true)
      }
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }

    act("Current time changes to on 01:00 and prealarm is fired") {
      currentHour = 1
      alarm.onAlarmFired()
    }

    act("Dismiss") {
      currentMinute++ // otherwise the test will think that it is time for the alarm again
      alarm.dismiss()
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_REMOVE_SKIP) }
    verify(exactly = 1) { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
  }

  @Test
  fun `non repeating alarm is disabled and cleaned up by skip`() {
    val alarm = createAlarm()
    act("Set on 01:00") { alarm.edit { copy(isEnabled = true, hour = 1) } }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
    assertThat(alarmSetterMock.calendar).isNotNull()

    act("RequestSkip") { alarm.requestSkip() }

    assertThat(store.alarms().blockingFirst().first().isEnabled).isFalse()
    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_REMOVE_SKIP) }
    assertThat(alarmSetterMock.calendar).isNull()
  }

  @Test
  fun `Repeating alarm is not disabled by skip`() {
    val alarm = createAlarm()
    act("Set on 1:00") {
      alarm.edit { copy(daysOfWeek = DaysOfWeek(0x7f), isEnabled = true, hour = 1) }
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
    assertThat(alarmSetterMock.calendar).isNotNull()

    act("RequestSkip") { alarm.requestSkip() }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_REMOVE_SKIP) }

    assertThat(store.alarms().blockingFirst().first().isEnabled).isTrue()
    assertThat(alarmSetterMock.calendar).isNotNull()
    // next repeating
    assertThat(alarmSetterMock.calendar?.get(Calendar.DAY_OF_YEAR)).isEqualTo(2)
  }

  @Test
  fun `Repeating alarm is not disabled`() {
    val alarm = createAlarm()
    act("Set on 1:00") {
      alarm.edit { copy(daysOfWeek = DaysOfWeek(0x7f), isEnabled = true, hour = 1) }
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
    assertThat(alarmSetterMock.calendar).isNotNull()

    act("RequestSkip") { alarm.requestSkip() }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_REMOVE_SKIP) }

    assertThat(store.alarms().blockingFirst().first().isEnabled).isTrue()
    assertThat(alarmSetterMock.calendar).isNotNull()
  }

  @Test
  fun `Repeating alarm shows proper next time`() {
    val alarm = createAlarm()
    act("Set on 1:00") {
      alarm.edit { copy(daysOfWeek = DaysOfWeek(0x7f), isEnabled = true, hour = 1) }
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
    assertThat(alarmSetterMock.calendar).isNotNull()

    act("RequestSkip") { alarm.requestSkip() }

    // next repeating
    assertThat(alarmSetterMock.calendar?.get(Calendar.DAY_OF_YEAR)).isEqualTo(2)
  }

  @Test
  fun `Repeating alarm shows proper next time when repeating is not every day`() {
    val alarm = createAlarm()
    act("Set on 1:00") {
      alarm.edit { copy(daysOfWeek = DaysOfWeek(0x3), isEnabled = true, hour = 1) }
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
    assertThat(alarmSetterMock.calendar).isNotNull()

    act("RequestSkip") { alarm.requestSkip() }

    // next repeating
    assertThat(alarmSetterMock.calendar?.get(Calendar.DAY_OF_YEAR)).isEqualTo(7)
  }

  @Test
  fun `Repeating alarm is back to business after skip has passed`() {
    val alarm = createAlarm()
    act("Set repeating on 01:00") {
      alarm.edit { copy(daysOfWeek = DaysOfWeek(0x7f), isEnabled = true, hour = 1) }
    }

    act("RequestSkip") { alarm.requestSkip() }

    assertThat(store.alarms().blockingFirst().first().skipping).isTrue()

    act("A day has passed") {
      currentDay++
      currentHour = 2
      alarm.onInexactAlarmFired()
    }

    assertThat(store.alarms().blockingFirst().first().skipping).isFalse()
  }

  @Test
  fun `When skipping alarm is disabled it is completely cleaned up`() {
    val alarm = createAlarm()
    act("Set on 1:00") {
      alarm.edit { copy(daysOfWeek = DaysOfWeek(0x7f), isEnabled = true, hour = 1) }
    }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SHOW_SKIP) }
    assertThat(alarmSetterMock.calendar).isNotNull()

    act("RequestSkip") { alarm.requestSkip() }
    act("Disable") { alarm.enable(false) }

    assertThat(alarmSetterMock.calendar).isNull()
  }

  @Test
  fun `autosilence sends an event and then becomes dismissed`() {
    val alarm = createAlarm()
    act("Set on 01:00") { alarm.edit { copy(isEnabled = true, hour = 1) } }

    act("Fired") { alarm.onAlarmFired() }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_ALERT_ACTION) }

    act("Autosilence") { alarm.onAlarmFired() }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ACTION_SOUND_EXPIRED) }
    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_DISMISS_ACTION) }
  }

  @Test
  fun `stores are updated before sending a snooze event`() {
    var nextTimeInStoreWhenBroadcasting: Calendar? = null
    every { stateNotifierMock.broadcastAlarmState(any(), any(), any()) } answers
        {
          nextTimeInStoreWhenBroadcasting = invocation.args[2] as Calendar?
        }
    val alarm = createAlarm()
    act("Set on 01:00") { alarm.edit { copy(isEnabled = true, minutes = 5, hour = 1) } }
    currentHour = 1
    currentMinute = 5
    act("Fired") { alarm.onAlarmFired() }
    act("Snooze") { alarm.snooze() }

    verify { stateNotifierMock.broadcastAlarmState(alarm.id, Intents.ALARM_SNOOZE_ACTION, any()) }
    assertThat(nextTimeInStoreWhenBroadcasting?.get(Calendar.MINUTE)).isEqualTo(15)
  }
}
