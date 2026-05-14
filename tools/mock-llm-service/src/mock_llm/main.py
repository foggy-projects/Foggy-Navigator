from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from .store.yaml_store import YamlResponseStore
from .store.script_store import ScriptStore
from .strategies.keyword import KeywordMatchStrategy
from .routes import openai, admin, anthropic, e2e
from .config import settings

# 初始化存储和策略
response_store = YamlResponseStore(settings.responses_dir)
script_store = ScriptStore()
match_strategy = KeywordMatchStrategy()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    print(f"Mock LLM Service started")
    print(f"Loaded {len(response_store.get_rules())} response rules")
    anthropic_rules = sum(1 for r in response_store.get_rules() if r.anthropic)
    print(f"  - OpenAI rules: {len(response_store.get_rules()) - anthropic_rules}")
    print(f"  - Anthropic-enabled rules: {anthropic_rules}")
    if settings.fixtures_dir:
        print(f"  - Fixtures dir: {settings.fixtures_dir}")
    yield
    # Shutdown
    print("Mock LLM Service stopped")


app = FastAPI(
    title="Mock LLM Service",
    description="OpenAI API + Anthropic Messages API 兼容的 Mock LLM 服务",
    version="2.0.0",
    lifespan=lifespan,
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注入到路由
openai.init_router(response_store, match_strategy, script_store)
admin.init_router(response_store)
anthropic.init_router(response_store, match_strategy, settings.fixtures_dir or None)
e2e.init_router(script_store)

# 注册路由
app.include_router(openai.router)
app.include_router(admin.router)
app.include_router(anthropic.router)
app.include_router(e2e.router)

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=settings.port)
