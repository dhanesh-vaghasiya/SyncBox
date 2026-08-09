import uuid
from datetime import datetime

from pydantic import EmailStr, Field, model_validator

from app.models import EmailStatus
from app.schemas.base import ORMBase


# ── Request schemas ───────────────────────────────────

class EmailCreate(ORMBase):
    recipient_email: EmailStr
    sender_email: EmailStr
    subject: str = Field(..., min_length=1, max_length=255)
    body_html: str | None = None
    body_text: str | None = None
    idempotency_key: str | None = Field(
        default=None,
        description="Client-supplied key to prevent duplicate sends on retry",
    )

    @model_validator(mode="after")
    def require_at_least_one_body(self):
        if not self.body_html and not self.body_text:
            raise ValueError("Either body_html or body_text must be provided")
        return self


# ── Response schemas ──────────────────────────────────

class EmailResponse(ORMBase):
    id: uuid.UUID
    user_id: uuid.UUID
    recipient_email: EmailStr
    sender_email: EmailStr
    subject: str
    status: EmailStatus
    provider_name: str | None
    attempt_count: int
    max_attempts: int
    next_retry_at: datetime | None
    last_error: str | None
    created_at: datetime
    updated_at: datetime


class EmailSummary(ORMBase):
    """Lightweight version for list endpoints — no body content."""
    id: uuid.UUID
    recipient_email: EmailStr
    subject: str
    status: EmailStatus
    created_at: datetime


class EmailListResponse(ORMBase):
    items: list[EmailSummary]
    total: int
    page: int
    page_size: int