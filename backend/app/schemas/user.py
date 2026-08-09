import uuid
from datetime import datetime

from pydantic import EmailStr, HttpUrl, Field

from app.models import UserStatus
from app.schemas.base import ORMBase


# ── Request schemas ───────────────────────────────────

class UserCreate(ORMBase):
    email: EmailStr
    company_name: str | None = None
    webhook_url: HttpUrl | None = None
    

class UserUpdate(ORMBase):
    company_name: str | None = None
    webhook_url: HttpUrl | None = None
    status: UserStatus | None = None


# ── Response schemas ──────────────────────────────────

class UserResponse(ORMBase):
    id: uuid.UUID
    email: EmailStr
    company_name: str | None
    webhook_url: HttpUrl | None
    status: UserStatus
    created_at: datetime
    updated_at: datetime
    # api_key and webhook_secret intentionally excluded — never echoed back


class UserWithApiKeyResponse(UserResponse):
    """Returned ONLY once, at creation/regeneration time."""
    api_key: str = Field(..., description="Shown once — store this securely")