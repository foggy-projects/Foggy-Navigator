from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from .store.yaml_store import YamlResponseStore
from .strategies.keyword import KeywordMatchStrategy
from .routes import openai, admin
from .config import settings

# 初始化存储和策略
response_store = YamlResponseStore(settings.responses_dir)
match_strategy = KeywordMatchStrategy()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    print(f"Mock LLM Service started")
    print(f"Loaded {len(response_store.get_rules())} response rules")
    yield
    # Shutdown
    print("Mock LLM Service stopped")


app = FastAPI(
    title="Mock LLM Service",
    description="OpenAI API 兼容的 Mock LLM 服务，用于测试和开发",
    version="1.0.0",
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
openai.init_router(response_store, match_strategy)
admin.init_router(response_store)

# 注册路由
app.include_router(openai.router)
app.include_router(admin.router)

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=settings.port)
