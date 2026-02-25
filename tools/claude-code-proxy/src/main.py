from fastapi import FastAPI
from src.api.endpoints import router as api_router
import uvicorn
import sys
from src.core.config import config

app = FastAPI(title="Claude-to-OpenAI API Proxy", version="1.0.0")

app.include_router(api_router)


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--help":
        print("Claude-to-OpenAI API Proxy v1.0.0")
        print("")
        print("Usage: python src/main.py")
        print("")
        print("Single-key mode (backward compatible):")
        print("  OPENAI_API_KEY      - Your OpenAI-compatible API key")
        print("  OPENAI_BASE_URL     - Base URL (default: https://api.openai.com/v1)")
        print("  BIG_MODEL           - Model for opus (default: gpt-4o)")
        print("  MIDDLE_MODEL        - Model for sonnet (default: BIG_MODEL)")
        print("  SMALL_MODEL         - Model for haiku (default: gpt-4o-mini)")
        print("")
        print("Multi-key pool mode:")
        print("  OPENAI_API_KEY_<NAME>   - Backend key (e.g. OPENAI_API_KEY_A)")
        print("  OPENAI_BASE_URL_<NAME>  - Per-key base URL (optional)")
        print("  BIG_MODEL_<NAME>        - Per-key opus model (optional)")
        print("  MIDDLE_MODEL_<NAME>     - Per-key sonnet model (optional)")
        print("  SMALL_MODEL_<NAME>      - Per-key haiku model (optional)")
        print("  KEY_MAPPING             - Client-to-backend mapping (optional)")
        print("    Format: client_key:A,B;client_key2:B")
        print("")
        print("Other settings:")
        print("  ANTHROPIC_API_KEY   - Client validation (single-key mode)")
        print(f"  HOST                - Server host (default: 0.0.0.0)")
        print(f"  PORT                - Server port (default: 8082)")
        print(f"  LOG_LEVEL           - Logging level (default: INFO)")
        print(f"  MAX_TOKENS_LIMIT    - Token limit (default: 4096)")
        print(f"  REQUEST_TIMEOUT     - Timeout in seconds (default: 90)")
        sys.exit(0)

    # Configuration summary
    key_pool = config.key_pool
    print("Claude-to-OpenAI API Proxy v1.0.0")
    print(f"Configuration loaded successfully")
    print(f"   Backend Keys: {len(key_pool.keys)} [{', '.join(key_pool.all_key_names())}]")
    for name, bk in key_pool.keys.items():
        print(f"   [{name}] base_url={bk.base_url}")
        print(f"         opus={bk.big_model}, sonnet={bk.middle_model}, haiku={bk.small_model}")
    if key_pool.has_mapping:
        print(f"   KEY_MAPPING: {len(key_pool.mapping)} client key(s)")
        for ck, backends in key_pool.mapping.items():
            masked = ck[:8] + "..." if len(ck) > 8 else ck
            print(f"     {masked} -> [{', '.join(backends)}]")
    print(f"   Max Tokens Limit: {config.max_tokens_limit}")
    print(f"   Request Timeout: {config.request_timeout}s")
    print(f"   Server: {config.host}:{config.port}")
    validation = "KEY_MAPPING" if key_pool.has_mapping else ("ANTHROPIC_API_KEY" if config.anthropic_api_key else "Disabled")
    print(f"   Client Validation: {validation}")
    print("")

    # Parse log level - extract just the first word to handle comments
    log_level = config.log_level.split()[0].lower()
    
    # Validate and set default if invalid
    valid_levels = ['debug', 'info', 'warning', 'error', 'critical']
    if log_level not in valid_levels:
        log_level = 'info'

    # Start server
    uvicorn.run(
        "src.main:app",
        host=config.host,
        port=config.port,
        log_level=log_level,
        reload=False,
    )


if __name__ == "__main__":
    main()
