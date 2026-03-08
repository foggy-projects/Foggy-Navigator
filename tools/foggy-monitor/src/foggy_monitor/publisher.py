"""Thread-safe RabbitMQ publisher with auto-reconnect and in-memory buffer."""

from __future__ import annotations

import json
import logging
import threading
import time
from collections import deque
from datetime import datetime, timezone
from typing import Any

import pika
import pika.exceptions

from .config import MonitorConfig

logger = logging.getLogger(__name__)


class RabbitMQPublisher:
    """Publishes messages to a RabbitMQ topic exchange.

    Features:
    - Thread-safe publish via a dedicated background thread
    - Auto-reconnect on connection loss
    - In-memory ring buffer (max ``config.buffer_size`` messages) when disconnected
    """

    def __init__(self, config: MonitorConfig) -> None:
        self._config = config
        self._buffer: deque[tuple[str, dict]] = deque(maxlen=config.buffer_size)
        self._lock = threading.Lock()
        self._connection: pika.BlockingConnection | None = None
        self._channel: pika.adapters.blocking_connection.BlockingChannel | None = None
        self._closed = False
        self._connect_thread: threading.Thread | None = None
        self._publish_thread: threading.Thread | None = None
        self._event = threading.Event()

        if config.enabled:
            self._start()

    # -- lifecycle -----------------------------------------------------------

    def _start(self) -> None:
        self._publish_thread = threading.Thread(
            target=self._publish_loop, daemon=True, name="foggy-monitor-publisher"
        )
        self._publish_thread.start()

    def close(self) -> None:
        self._closed = True
        self._event.set()
        try:
            if self._connection and self._connection.is_open:
                self._connection.close()
        except Exception:
            pass

    # -- public API ----------------------------------------------------------

    def publish(self, routing_key: str, payload: dict[str, Any]) -> None:
        """Enqueue a message for publishing.  Never blocks, never raises."""
        if self._closed or not self._config.enabled:
            return
        envelope = {
            "service": self._config.service_name,
            "instance": self._config.instance_id or self._config.service_name,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "type": routing_key.split(".")[1] if "." in routing_key else "unknown",
            "payload": payload,
        }
        with self._lock:
            self._buffer.append((routing_key, envelope))
        self._event.set()

    # -- background loop -----------------------------------------------------

    def _publish_loop(self) -> None:
        while not self._closed:
            # Ensure connection
            if not self._is_connected():
                self._try_connect()

            # Drain buffer
            while not self._closed and self._is_connected():
                item = None
                with self._lock:
                    if self._buffer:
                        item = self._buffer.popleft()
                if item is None:
                    break
                routing_key, envelope = item
                try:
                    self._channel.basic_publish(  # type: ignore[union-attr]
                        exchange=self._config.exchange,
                        routing_key=routing_key,
                        body=json.dumps(envelope, ensure_ascii=False),
                        properties=pika.BasicProperties(
                            content_type="application/json",
                            delivery_mode=1,  # non-persistent (monitoring data)
                        ),
                    )
                except Exception:
                    # Put message back and reconnect
                    with self._lock:
                        self._buffer.appendleft(item)
                    self._close_connection()
                    break

            # Wait for new messages or timeout (for reconnect retry)
            self._event.wait(timeout=5.0)
            self._event.clear()

    # -- connection helpers --------------------------------------------------

    def _is_connected(self) -> bool:
        return (
            self._connection is not None
            and self._connection.is_open
            and self._channel is not None
            and self._channel.is_open
        )

    def _try_connect(self) -> None:
        try:
            credentials = pika.PlainCredentials(
                self._config.rabbitmq_user, self._config.rabbitmq_password
            )
            params = pika.ConnectionParameters(
                host=self._config.rabbitmq_host,
                port=self._config.rabbitmq_port,
                virtual_host=self._config.rabbitmq_vhost,
                credentials=credentials,
                heartbeat=30,
                blocked_connection_timeout=10,
                connection_attempts=1,
                retry_delay=0,
                socket_timeout=5,
            )
            self._connection = pika.BlockingConnection(params)
            self._channel = self._connection.channel()
            # Declare exchange (idempotent)
            self._channel.exchange_declare(
                exchange=self._config.exchange,
                exchange_type="topic",
                durable=True,
            )
            logger.info(
                "foggy-monitor: connected to RabbitMQ %s:%s",
                self._config.rabbitmq_host,
                self._config.rabbitmq_port,
            )
        except Exception as exc:
            logger.debug("foggy-monitor: RabbitMQ connect failed: %s", exc)
            self._close_connection()
            # Back off before next attempt
            time.sleep(5)

    def _close_connection(self) -> None:
        try:
            if self._connection and self._connection.is_open:
                self._connection.close()
        except Exception:
            pass
        self._connection = None
        self._channel = None
