import os
import sys
from typing import Dict, List, Optional

from src.core.key_pool import BackendKey, KeyPool

# Configuration
class Config:
    def __init__(self):
        # Global defaults
        self.openai_base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1")
        self.azure_api_version = os.environ.get("AZURE_API_VERSION")  # For Azure OpenAI
        self.host = os.environ.get("HOST", "0.0.0.0")
        self.port = int(os.environ.get("PORT", "8082"))
        self.log_level = os.environ.get("LOG_LEVEL", "INFO")
        self.max_tokens_limit = int(os.environ.get("MAX_TOKENS_LIMIT", "4096"))
        self.min_tokens_limit = int(os.environ.get("MIN_TOKENS_LIMIT", "100"))

        # Connection settings
        self.request_timeout = int(os.environ.get("REQUEST_TIMEOUT", "90"))
        self.max_retries = int(os.environ.get("MAX_RETRIES", "2"))

        # Context truncation settings (for passthrough retry on input-length errors)
        self.truncation_tool_result_limit = int(os.environ.get("TRUNCATION_TOOL_RESULT_LIMIT", "30000"))
        self.truncation_keep_head_messages = int(os.environ.get("TRUNCATION_KEEP_HEAD_MESSAGES", "4"))
        self.truncation_keep_tail_messages = int(os.environ.get("TRUNCATION_KEEP_TAIL_MESSAGES", "40"))

        # Global model settings (fallback for per-key)
        self.big_model = os.environ.get("BIG_MODEL", "gpt-4o")
        self.middle_model = os.environ.get("MIDDLE_MODEL", self.big_model)
        self.small_model = os.environ.get("SMALL_MODEL", "gpt-4o-mini")

        # Build key pool (multi-key or single-key fallback)
        self.key_pool = self._build_key_pool()

        # Keep openai_api_key for backward compat (first key in pool)
        first_key = list(self.key_pool.keys.values())[0]
        self.openai_api_key = first_key.api_key

        # Add Anthropic API key for client validation (legacy single-key mode)
        # Strip quotes that may be included literally by Docker --env-file
        raw_anthropic_key = os.environ.get("ANTHROPIC_API_KEY", "").strip().strip('"').strip("'")
        self.anthropic_api_key = raw_anthropic_key if raw_anthropic_key else None
        if not self.anthropic_api_key and not self.key_pool.has_mapping:
            print("Warning: Neither ANTHROPIC_API_KEY nor KEY_MAPPING set. Client API key validation will be disabled.")

    def _build_key_pool(self) -> KeyPool:
        """Scan env for OPENAI_API_KEY_* and build the key pool."""
        env_vars = dict(os.environ)

        # Discover named keys: OPENAI_API_KEY_<NAME>
        named_keys: Dict[str, BackendKey] = {}
        prefix = "OPENAI_API_KEY_"
        for env_key, env_value in env_vars.items():
            if env_key.startswith(prefix) and env_value.strip():
                name = env_key[len(prefix):]
                if not name:
                    continue
                api_key = env_value.strip()
                base_url = env_vars.get(f"OPENAI_BASE_URL_{name}", self.openai_base_url)
                big = env_vars.get(f"BIG_MODEL_{name}", self.big_model)
                middle = env_vars.get(f"MIDDLE_MODEL_{name}", big)
                small = env_vars.get(f"SMALL_MODEL_{name}", self.small_model)
                passthrough = env_vars.get(f"PASSTHROUGH_{name}", "").lower() in ("true", "1", "yes")
                named_keys[name] = BackendKey(
                    name=name,
                    api_key=api_key,
                    base_url=base_url,
                    big_model=big,
                    middle_model=middle,
                    small_model=small,
                    passthrough=passthrough,
                )

        # Fallback: single OPENAI_API_KEY → "DEFAULT"
        if not named_keys:
            single_key = os.environ.get("OPENAI_API_KEY")
            if not single_key:
                raise ValueError("No OPENAI_API_KEY or OPENAI_API_KEY_* found in environment variables")
            named_keys["DEFAULT"] = BackendKey(
                name="DEFAULT",
                api_key=single_key,
                base_url=self.openai_base_url,
                big_model=self.big_model,
                middle_model=self.middle_model,
                small_model=self.small_model,
            )
        else:
            if os.environ.get("OPENAI_API_KEY"):
                print("Note: OPENAI_API_KEY ignored because OPENAI_API_KEY_* keys were found.")

        # Parse KEY_MAPPING
        mapping: Dict[str, List[str]] = {}
        raw_mapping = os.environ.get("KEY_MAPPING", "").strip()
        if raw_mapping:
            # Format: client_key:backend1,backend2;client_key2:backend3
            for pair in raw_mapping.split(";"):
                pair = pair.strip()
                if not pair or ":" not in pair:
                    continue
                client_key, backends_str = pair.split(":", 1)
                client_key = client_key.strip()
                backends = [b.strip() for b in backends_str.split(",") if b.strip()]
                # Validate backend names exist
                valid_backends = [b for b in backends if b in named_keys]
                if valid_backends:
                    mapping[client_key] = valid_backends
                else:
                    print(f"Warning: KEY_MAPPING client '{client_key}' has no valid backend keys, skipping")

        return KeyPool(keys=named_keys, mapping=mapping if mapping else None)

    def validate_api_key(self):
        """Basic API key validation (checks first key in pool)"""
        if not self.openai_api_key:
            return False
        return True

    def validate_client_api_key(self, client_api_key):
        """Validate client's API key.

        - If KEY_MAPPING is configured, delegate to key_pool.
        - Else if ANTHROPIC_API_KEY is set, check exact match.
        - Else skip validation.
        """
        if self.key_pool.has_mapping:
            return self.key_pool.validate_client_key(client_api_key)

        # Legacy single-key mode
        if not self.anthropic_api_key:
            return True
        return client_api_key == self.anthropic_api_key

    def get_custom_headers(self):
        """Get custom headers from environment variables"""
        custom_headers = {}

        # Get all environment variables
        env_vars = dict(os.environ)

        # Find CUSTOM_HEADER_* environment variables
        for env_key, env_value in env_vars.items():
            if env_key.startswith('CUSTOM_HEADER_'):
                # Convert CUSTOM_HEADER_KEY to Header-Key
                # Remove 'CUSTOM_HEADER_' prefix and convert to header format
                header_name = env_key[14:]  # Remove 'CUSTOM_HEADER_' prefix

                if header_name:  # Make sure it's not empty
                    # Convert underscores to hyphens for HTTP header format
                    header_name = header_name.replace('_', '-')
                    custom_headers[header_name] = env_value

        return custom_headers

try:
    config = Config()
    key_names = config.key_pool.all_key_names()
    print(f" Configuration loaded: {len(key_names)} backend key(s) [{', '.join(key_names)}]")
except Exception as e:
    print(f"Configuration Error: {e}")
    sys.exit(1)
