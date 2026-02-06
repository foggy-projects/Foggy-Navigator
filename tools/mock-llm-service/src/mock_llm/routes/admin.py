from fastapi import APIRouter
from typing import List
from ..models import ResponseRule
from ..store.yaml_store import YamlResponseStore

router = APIRouter(prefix="/admin", tags=["Admin"])

response_store: YamlResponseStore = None


def init_router(store: YamlResponseStore):
    global response_store
    response_store = store


@router.get("/responses", response_model=List[ResponseRule])
async def list_responses():
    """列出所有响应规则"""
    return response_store.get_rules()


@router.post("/reload")
async def reload_responses():
    """重新加载响应配置"""
    response_store.reload()
    return {"message": "Responses reloaded", "count": len(response_store.get_rules())}


@router.get("/health")
async def health_check():
    """健康检查"""
    return {"status": "ok", "rules_count": len(response_store.get_rules())}
