from fastapi import APIRouter, HTTPException, Query

from ..models import DebugRequestRecord, ScriptRegistration, ScriptRegistrationResult
from ..store.script_store import ScriptStore

router = APIRouter()

script_store: ScriptStore = None


def init_router(store: ScriptStore):
    global script_store
    script_store = store


@router.post("/__e2e/scripts", response_model=ScriptRegistrationResult)
async def register_script(script: ScriptRegistration):
    try:
        return script_store.register(script)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.delete("/__e2e/scripts/{trace_id}")
async def cleanup_script(trace_id: str):
    removed = script_store.cleanup(trace_id)
    return {"traceId": trace_id, "removed": removed}


@router.get("/__debug/requests", response_model=list[DebugRequestRecord])
async def debug_requests(trace_id: str = Query(alias="traceId")):
    return script_store.debug_requests(trace_id)
