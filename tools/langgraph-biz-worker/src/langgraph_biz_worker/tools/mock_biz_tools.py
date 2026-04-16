"""Mock business tools for Phase 1 validation.

These tools simulate external TMS system calls without any real network
dependency.  They will be replaced by real tool adapters when external
system integration begins.
"""

from __future__ import annotations

from typing import Any


def mock_get_order(order_id: str) -> dict[str, Any]:
    """Simulate fetching order details."""
    return {
        "order_id": order_id,
        "status": "delayed",
        "delay_minutes": 45,
        "vehicle_id": "V09",
        "origin": "warehouse_A",
        "destination": "customer_B",
        "created_at": "2026-04-13T08:00:00Z",
    }


def mock_get_vehicle_status(vehicle_id: str) -> dict[str, Any]:
    """Simulate fetching vehicle status."""
    return {
        "vehicle_id": vehicle_id,
        "status": "breakdown",
        "location": "highway_section_3",
        "last_heartbeat": "2026-04-13T08:30:00Z",
        "battery_level": 0.15,
        "error_code": "E_MOTOR_OVERHEAT",
    }


def mock_search_incidents(query: str) -> list[dict[str, Any]]:
    """Simulate searching incident records."""
    return [
        {
            "incident_id": "INC_001",
            "type": "vehicle_breakdown",
            "vehicle_id": "V09",
            "reported_at": "2026-04-13T08:35:00Z",
            "severity": "high",
        },
    ]
