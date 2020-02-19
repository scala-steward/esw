package esw.gateway.server.metrics

import csw.prefix.models.Subsystem
import esw.gateway.server.metrics.Metrics._

object EventMetrics {
  val getEventLabel                      = "get_event"
  val publishEventLabel                  = "publish_event"
  private val subscribeEventLabel        = "subscribe_event"
  private val patternSubscribeEventLabel = "pattern_subscribe_event"

  private[metrics] val eventCounterMetricName = "gateway_event_service_requests_total"
  private lazy val eventCounter =
    counter(
      metricName = eventCounterMetricName,
      help = "Total event service requests passing through gateway",
      labelNames = "api"
    )

  private[metrics] val subscribeGaugeMetricName = "gateway_event_service_active_subscribers_total"
  private lazy val subscribeGauge =
    gauge(
      metricName = subscribeGaugeMetricName,
      help = "Total active event subscribers",
      labelNames = "api"
    )

  private[metrics] val patternSubscribeGaugeMetricName = "gateway_event_service_active_pattern_subscribers_total"
  private lazy val patternSubscribeGauge =
    gauge(
      metricName = patternSubscribeGaugeMetricName,
      help = "Total active pattern event subscribers",
      labelNames = "api",
      "subsystem",
      "pattern"
    )

  def incEventCounter(label: String): Unit = eventCounter.labels(label).inc()

  def incSubscriberGauge(): Unit = subscribeGauge.labels(subscribeEventLabel).inc()
  def decSubscriberGauge(): Unit = subscribeGauge.labels(subscribeEventLabel).dec()

  def incPatternSubscriberGauge(subsystem: Subsystem, pattern: String): Unit =
    patternSubscribeGauge.labels(patternSubscribeEventLabel, subsystem.name, pattern).inc()
  def decPatternSubscriberGauge(subsystem: Subsystem, pattern: String): Unit =
    patternSubscribeGauge.labels(patternSubscribeEventLabel, subsystem.name, pattern).dec()
}
