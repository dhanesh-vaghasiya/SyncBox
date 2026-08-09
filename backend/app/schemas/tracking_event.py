import uuid
from datetime import datetime
from typing import Any

# pyrefly: ignore [missing-import]
from app.models.models import EventType, EventSource
from app.schemas.base import ORMBase


# ── Response schemas ──────────────────────────────────
# TrackingEvents are never client-created directly — they're generated
# internally or by inbound provider webhooks — so this module is response-only.

class TrackingEventResponse(ORMBase):
    id: uuid.UUID
    email_id: uuid.UUID
    event_type: EventType
    source: EventSource
    occurred_at: datetime
    payload: dict[str, Any] | None = None


class EmailHistoryResponse(ORMBase):
    """Full email + its event timeline — for GET /emails/{id}/events"""
    email_id: uuid.UUID
    current_status: str
    events: list[TrackingEventResponse]


# ── Inbound provider webhook payload (raw, pre-normalization) ─

class ProviderWebhookPayload(ORMBase):
    """
    Generic envelope for whatever the provider adapter parses out of
    the raw webhook body, before it's turned into a TrackingEvent.
    Each ProviderAdapter.parse_status_webhook() should return this shape.
    """
    provider_event_id: str
    event_type: EventType
    email_provider_message_id: str
    occurred_at: datetime
    raw_payload: dict[str, Any]