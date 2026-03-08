"""Unit tests for key_pool.py module."""

import pytest
import threading
import time
from src.core.key_pool import BackendKey, KeyPool


@pytest.mark.unit
class TestBackendKey:
    """Test BackendKey dataclass."""

    def test_backend_key_creation(self):
        """Test creating a BackendKey with all fields."""
        key = BackendKey(
            name="test-key",
            api_key="sk-test-key-123",
            base_url="https://api.openai.com/v1",
            big_model="gpt-4o",
            middle_model="gpt-4o",
            small_model="gpt-4o-mini",
            passthrough=False,
        )

        assert key.name == "test-key"
        assert key.api_key == "sk-test-key-123"
        assert key.base_url == "https://api.openai.com/v1"
        assert key.big_model == "gpt-4o"
        assert key.middle_model == "gpt-4o"
        assert key.small_model == "gpt-4o-mini"
        assert key.passthrough is False

    def test_backend_key_defaults(self):
        """Test BackendKey with default passthrough value."""
        key = BackendKey(
            name="default",
            api_key="sk-key",
            base_url="https://api.openai.com/v1",
            big_model="gpt-4o",
            middle_model="gpt-4o",
            small_model="gpt-4o-mini",
        )

        assert key.passthrough is False  # Default value

    def test_backend_key_passthrough_true(self):
        """Test BackendKey with passthrough=True."""
        key = BackendKey(
            name="dashscope",
            api_key="sk-dashscope",
            base_url="https://dashscope.aliyuncs.com/v1",
            big_model="glm-4",
            middle_model="glm-4",
            small_model="glm-3-turbo",
            passthrough=True,
        )

        assert key.passthrough is True


@pytest.mark.unit
class TestKeyPool:
    """Test KeyPool class functionality."""

    def test_keypool_initialization(self, sample_backend_keys):
        """Test KeyPool initialization with keys."""
        pool = KeyPool(keys=sample_backend_keys, mapping=None)

        assert len(pool.keys) == 2
        assert pool.has_mapping is False
        assert pool._global_counter == 0
        assert pool._client_counters == {}

    def test_keypool_with_mapping(self):
        """Test KeyPool with client-to-backend mapping."""
        keys = {
            "backend1": BackendKey(
                name="backend1",
                api_key="sk-b1",
                base_url="https://api1.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
            "backend2": BackendKey(
                name="backend2",
                api_key="sk-b2",
                base_url="https://api2.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
        }
        mapping = {"client1": ["backend1"], "client2": ["backend1", "backend2"]}

        pool = KeyPool(keys=keys, mapping=mapping)

        assert pool.has_mapping is True
        assert len(pool.mapping) == 2
        assert "client1" in pool.mapping
        assert "client2" in pool.mapping
        assert pool.mapping["client1"] == ["backend1"]
        assert pool.mapping["client2"] == ["backend1", "backend2"]

    def test_keypool_empty_mapping(self):
        """Test KeyPool with empty mapping (should set has_mapping to False)."""
        keys = {"key1": BackendKey(
            name="key1",
            api_key="sk-key1",
            base_url="https://api1.com",
            big_model="gpt-4o",
            middle_model="gpt-4o",
            small_model="gpt-4o-mini",
        )}
        pool = KeyPool(keys=keys, mapping={})

        assert pool.has_mapping is False

    def test_all_key_names(self, sample_backend_keys):
        """Test getting all key names from the pool."""
        pool = KeyPool(keys=sample_backend_keys, mapping=None)
        names = pool.all_key_names()

        assert len(names) == 2
        assert "key1" in names
        assert "key2" in names

    def test_validate_client_key_without_mapping(self, sample_backend_keys):
        """Test validate_client_key when no mapping is configured (always returns True)."""
        pool = KeyPool(keys=sample_backend_keys, mapping=None)

        assert pool.validate_client_key("any-key") is True
        assert pool.validate_client_key("") is True
        assert pool.validate_client_key(None) is True

    def test_validate_client_key_with_mapping_valid(self):
        """Test validate_client_key with valid mapping key."""
        keys = {
            "backend1": BackendKey(
                name="backend1",
                api_key="sk-b1",
                base_url="https://api1.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            )
        }
        mapping = {"client1": ["backend1"]}

        pool = KeyPool(keys=keys, mapping=mapping)

        assert pool.validate_client_key("client1") is True
        assert pool.validate_client_key("client2") is False
        assert pool.validate_client_key("") is False

    def test_get_next_key_no_mapping_round_robin(self, sample_backend_keys):
        """Test get_next_key round-robin without client mapping."""
        pool = KeyPool(keys=sample_backend_keys, mapping=None)

        # Get next key multiple times and verify rotation
        keys_in_order = [pool.get_next_key().name for _ in range(4)]

        assert keys_in_order == ["key1", "key2", "key1", "key2"]

    def test_get_next_key_with_client_mapping(self):
        """Test get_next_key with client-specific mapping."""
        keys = {
            "backend1": BackendKey(
                name="backend1",
                api_key="sk-b1",
                base_url="https://api1.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
            "backend2": BackendKey(
                name="backend2",
                api_key="sk-b2",
                base_url="https://api2.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
            "backend3": BackendKey(
                name="backend3",
                api_key="sk-b3",
                base_url="https://api3.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
        }
        mapping = {
            "client1": ["backend1"],
            "client2": ["backend1", "backend2"],
        }

        pool = KeyPool(keys=keys, mapping=mapping)

        # Client1 should always get backend1
        assert pool.get_next_key("client1").name == "backend1"
        assert pool.get_next_key("client1").name == "backend1"

        # Client2 should rotate between backend1 and backend2
        assert pool.get_next_key("client2").name == "backend1"
        assert pool.get_next_key("client2").name == "backend2"
        assert pool.get_next_key("client2").name == "backend1"

        # Unknown client should rotate across all keys
        assert pool.get_next_key("unknown").name in ["backend1", "backend2", "backend3"]

    def test_get_next_key_without_client_key(self, sample_backend_keys):
        """Test get_next_key without client key (uses global rotation)."""
        pool = KeyPool(keys=sample_backend_keys, mapping=None)

        keys_in_order = [pool.get_next_key(None).name for _ in range(4)]

        assert keys_in_order == ["key1", "key2", "key1", "key2"]

    def test_get_next_key_single_key(self):
        """Test get_next_key with only one key in pool."""
        keys = {
            "only": BackendKey(
                name="only",
                api_key="sk-only",
                base_url="https://api.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            )
        }
        pool = KeyPool(keys=keys, mapping=None)

        for _ in range(5):
            assert pool.get_next_key().name == "only"

    def test_get_next_key_multiple_clients_independent_counters(self):
        """Test that different clients maintain independent rotation counters."""
        keys = {
            "backend1": BackendKey(
                name="backend1",
                api_key="sk-b1",
                base_url="https://api1.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
            "backend2": BackendKey(
                name="backend2",
                api_key="sk-b2",
                base_url="https://api2.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
        }
        mapping = {
            "client1": ["backend1", "backend2"],
            "client2": ["backend1", "backend2"],
        }

        pool = KeyPool(keys=keys, mapping=mapping)

        # Client1 sequence
        assert pool.get_next_key("client1").name == "backend1"
        assert pool.get_next_key("client2").name == "backend1"
        assert pool.get_next_key("client1").name == "backend2"
        assert pool.get_next_key("client2").name == "backend2"
        assert pool.get_next_key("client1").name == "backend1"

    def test_get_next_key_empty_pool_raises_error(self):
        """Test get_next_key raises ValueError when pool is empty."""
        pool = KeyPool(keys={}, mapping=None)

        with pytest.raises(ValueError, match="No backend keys available in pool"):
            pool.get_next_key()

    def test_get_next_key_invalid_client_mapping(self):
        """Test get_next_key with client that has no valid backends in mapping."""
        keys = {
            "backend1": BackendKey(
                name="backend1",
                api_key="sk-b1",
                base_url="https://api1.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            )
        }
        mapping = {"client1": []}  # Empty backend list

        pool = KeyPool(keys=keys, mapping=mapping)

        # With empty backend list in mapping, should raise ValueError
        with pytest.raises(ValueError, match="No backend keys available in pool"):
            pool.get_next_key("client1")

    @pytest.mark.unit
    def test_keypool_thread_safety(self, sample_backend_keys):
        """Test that KeyPool is thread-safe for concurrent access."""
        pool = KeyPool(keys=sample_backend_keys, mapping=None)
        results = []
        num_threads = 10
        requests_per_thread = 100

        def worker():
            for _ in range(requests_per_thread):
                key = pool.get_next_key()
                results.append(key.name)

        threads = [threading.Thread(target=worker) for _ in range(num_threads)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(results) == num_threads * requests_per_thread
        # Verify both keys were used
        assert "key1" in results
        assert "key2" in results

    def test_keypool_global_counter_independence(self, sample_backend_keys):
        """Test that global counter is independent of client-specific counters."""
        pool = KeyPool(keys=sample_backend_keys, mapping=None)

        # Use global counter (2 keys, so counter wraps around)
        pool.get_next_key()  # idx 0 -> selects key1, counter becomes 1
        pool.get_next_key()  # idx 1 -> selects key2, counter becomes 0 (mod 2)

        # Counter is maintained in _client_counters["__global__"]
        # After 2 calls with 2 keys, the counter is back to 0 due to modulo
        assert pool._client_counters.get("__global__", 0) == 0

    def test_keypool_client_counter_initialization(self):
        """Test that client counters are initialized on first access."""
        keys = {
            "backend1": BackendKey(
                name="backend1",
                api_key="sk-b1",
                base_url="https://api1.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
            "backend2": BackendKey(
                name="backend2",
                api_key="sk-b2",
                base_url="https://api2.com",
                big_model="gpt-4o",
                middle_model="gpt-4o",
                small_model="gpt-4o-mini",
            ),
        }
        mapping = {"client1": ["backend1", "backend2"]}

        pool = KeyPool(keys=keys, mapping=mapping)

        assert "client1" not in pool._client_counters

        pool.get_next_key("client1")

        assert "client1" in pool._client_counters