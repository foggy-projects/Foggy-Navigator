from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .config import settings

_bearer_scheme = HTTPBearer(auto_error=False)


async def verify_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> None:
    """Validate the Bearer token carried in the ``Authorization`` header.

    When ``worker_token`` is empty the check is skipped entirely so that the
    service can run in *dev mode* without any pre-shared secret.
    """
    if not settings.worker_token:
        return

    if credentials is None or credentials.credentials != settings.worker_token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
