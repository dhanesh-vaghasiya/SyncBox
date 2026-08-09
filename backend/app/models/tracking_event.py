import enum
import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import String, ForeignKey, DateTime, Enum as SAEnum
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, utc_now

if TYPE_CHECKING:
    from app.models.email import Email


class EventType(str, enum.Enum):
    QUEUED = "QUEUED"
    PROCESSING_STARTED = "PROCESSING_STARTED"
    SENT = "SENT"
    DELIVERED = "DELIVERED"
    BOUNCED = "BOUNCED"
    COMPLAINED = "COMPLAINED"
    OPENED = "OPENED"
    CLICKED = "CLICKED"
    FAILED = "FAILED"
    RETRY_SCHEDULED = "RETRY_SCHEDULED"
    PERMANENTLY_FAILED = "PERMANENTLY_FAILED"


class EventSource(str, enum.Enum):
    SYSTEM = "SYSTEM"      # generated internally (queued, processing, retry_scheduled)
    PROVIDER = "PROVIDER"  # came from an inbound provider webhook


class TrackingEvent(Base):
    __tablename__ = "tracking_events"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )

    email_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("emails.id"), nullable=False
    )
    email: Mapped["Email"] = relationship(back_populates="tracking_events")

    event_type: Mapped[EventType] = mapped_column(
        SAEnum(EventType, name="event_type"), nullable=False
    )

    provider_event_id: Mapped[str | None] = mapped_column(String, unique=True)

    source: Mapped[EventSource] = mapped_column(
        SAEnum(EventSource, name="event_source"), nullable=False
    )

    payload: Mapped[dict | None] = mapped_column(JSONB)

    occurred_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, nullable=False
    )

    