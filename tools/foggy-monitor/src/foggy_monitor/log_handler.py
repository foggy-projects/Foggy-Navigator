"""Python logging.Handler that publishes WARNING+ log records to RabbitMQ."""

from __future__ import annotations

import logging
import traceback
from typing import Optional

from .config import MonitorConfig
from .publisher import RabbitMQPublisher

# Loggers whose messages must never be forwarded (prevents recursion)
_SUPPRESSED_LOGGERS = frozenset({"pika", "foggy_monitor", "urllib3"})


class RabbitMQLogHandler(logging.Handler):
    """Forwards log records at WARNING level and above to RabbitMQ.

    Filters out logs from pika / foggy_monitor to prevent infinite recursion.
    """

    def __init__(self, publisher: RabbitMQPublisher, config: MonitorConfig) -> None:
        level = getattr(logging, config.min_level.upper(), logging.WARNING)
        super().__init__(level=level)
        self._publisher = publisher
        self._config = config

    def emit(self, record: logging.LogRecord) -> None:
        try:
            # Suppress our own and pika's logs
            if any(record.name.startswith(s) for s in _SUPPRESSED_LOGGERS):
                return

            level = record.levelname
            routing_key = f"monitor.log.{self._config.service_name}.{level.lower()}"

            stack_trace: Optional[str] = None
            if record.exc_info and record.exc_info[1]:
                stack_trace = "".join(traceback.format_exception(*record.exc_info))

            payload = {
                "level": level,
                "logger": record.name,
                "message": record.getMessage(),
                "stackTrace": stack_trace,
            }

            self._publisher.publish(routing_key, payload)
        except Exception:
            # Never let monitoring break the application
            pass


# -- convenience wiring ------------------------------------------------------

_publisher: Optional[RabbitMQPublisher] = None


def setup_monitoring(
    service_name: str,
    instance_id: str = "",
    **overrides: str,
) -> Optional[RabbitMQPublisher]:
    """One-liner to set up monitoring.  Safe to call unconditionally.

    Returns the publisher (or ``None`` if monitoring is disabled).

    Usage::

        from foggy_monitor import setup_monitoring
        publisher = setup_monitoring("worker", instance_id="worker-01")

    The function reads ``FOGGY_MONITOR_*`` environment variables.  You can
    force-enable or override any config field via ``**overrides``::

        setup_monitoring("proxy", enabled="true", rabbitmq_host="10.0.0.5")
    """
    global _publisher
    try:
        extra = {"service_name": service_name}
        if instance_id:
            extra["instance_id"] = instance_id
        extra.update(overrides)
        config = MonitorConfig(**extra)  # type: ignore[arg-type]

        if not config.enabled:
            logging.getLogger(__name__).debug("foggy-monitor: disabled (FOGGY_MONITOR_ENABLED != true)")
            return None

        _publisher = RabbitMQPublisher(config)
        handler = RabbitMQLogHandler(_publisher, config)
        logging.root.addHandler(handler)
        logging.getLogger(__name__).info(
            "foggy-monitor: attached RabbitMQ handler for %s (level >= %s)",
            service_name,
            config.min_level,
        )
        return _publisher
    except Exception as exc:
        logging.getLogger(__name__).warning("foggy-monitor: setup failed: %s", exc)
        return None
