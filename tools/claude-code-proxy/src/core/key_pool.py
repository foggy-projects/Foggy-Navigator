"""Multi-key pool with client mapping and round-robin selection."""

from dataclasses import dataclass
from typing import Dict, List, Optional
import threading


@dataclass
class BackendKey:
    """A backend API key with its associated configuration."""
    name: str            # "A", "B", "DEFAULT"
    api_key: str
    base_url: str
    big_model: str
    middle_model: str
    small_model: str
    passthrough: bool = False  # Skip format conversion, forward raw Claude requests


class KeyPool:
    """Manages a pool of backend keys with round-robin selection and client mapping."""

    def __init__(
        self,
        keys: Dict[str, BackendKey],
        mapping: Optional[Dict[str, List[str]]] = None,
    ):
        self.keys = keys
        self.mapping = mapping or {}
        self._global_counter = 0
        self._client_counters: Dict[str, int] = {}
        self._lock = threading.Lock()

    @property
    def has_mapping(self) -> bool:
        return len(self.mapping) > 0

    def all_key_names(self) -> List[str]:
        return list(self.keys.keys())

    def validate_client_key(self, client_api_key: str) -> bool:
        """Check if a client key is allowed.

        - No mapping configured: any client key is accepted.
        - Mapping configured: client key must exist in the mapping.
        """
        if not self.has_mapping:
            return True
        return client_api_key in self.mapping

    def get_next_key(self, client_api_key: Optional[str] = None) -> BackendKey:
        """Get the next backend key via round-robin.

        - If KEY_MAPPING exists and client key matches, rotate within that client's pool.
        - Otherwise rotate across all backend keys.
        """
        if self.has_mapping and client_api_key and client_api_key in self.mapping:
            pool_names = self.mapping[client_api_key]
        else:
            pool_names = list(self.keys.keys())

        if not pool_names:
            raise ValueError("No backend keys available in pool")

        with self._lock:
            if self.has_mapping and client_api_key:
                counter_key = client_api_key
            else:
                counter_key = "__global__"

            idx = self._client_counters.get(counter_key, 0)
            selected_name = pool_names[idx % len(pool_names)]
            self._client_counters[counter_key] = (idx + 1) % len(pool_names)

        return self.keys[selected_name]
