"""Configuration for foggy-monitor, driven by FOGGY_MONITOR_ environment variables."""

from __future__ import annotations

from pydantic_settings import BaseSettings


class MonitorConfig(BaseSettings):
    """All fields can be overridden via environment variables with FOGGY_MONITOR_ prefix."""

    model_config = {"env_prefix": "FOGGY_MONITOR_"}

    enabled: bool = False
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_user: str = "foggy"
    rabbitmq_password: str = "foggy@monitor123"
    rabbitmq_vhost: str = "/"
    service_name: str = "unknown"
    instance_id: str = ""
    exchange: str = "foggy.events"
    buffer_size: int = 1000
    min_level: str = "WARNING"
