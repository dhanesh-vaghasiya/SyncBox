import enum
import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    String, Text, Integer, ForeignKey, DateTime, Enum as SAEnum
)
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


def utc_now() -> datetime:
    return datetime.now(timezone.utc)

# ── User ──────────────────────────────────────────────
# Represents the client application/account using the service,
# not the end email recipient.

class UserStatus(str, enum.Enum):
    ACTIVE = "ACTIVE"
    SUSPENDED = "SUSPENDED"
    DISABLED = "DISABLED"


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    email: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    company_name: Mapped[str | None] = mapped_column(String)
    api_key: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    webhook_url: Mapped[str | None] = mapped_column(String)
    webhook_secret: Mapped[str | None] = mapped_column(String)

    status: Mapped[UserStatus] = mapped_column(
        SAEnum(UserStatus, name="user_status"),
        nullable=False,
        default=UserStatus.ACTIVE,
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now, nullable=False
    )

    emails: Mapped[list["Email"]] = relationship(back_populates="user")


# ── Email ─────────────────────────────────────────────

class EmailStatus(str, enum.Enum):
    QUEUED = "QUEUED"
    PROCESSING = "PROCESSING"
    SENT = "SENT"
    DELIVERED = "DELIVERED"
    FAILED = "FAILED"
    RETRY = "RETRY"
    PERMANENTLY_FAILED = "PERMANENTLY_FAILED"
    BOUNCED = "BOUNCED"
    COMPLAINED = "COMPLAINED"


class Email(Base):
    __tablename__ = "emails"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    user: Mapped["User"] = relationship(back_populates="emails")

    idempotency_key: Mapped[str | None] = mapped_column(String, unique=True)

    recipient_email: Mapped[str] = mapped_column(String, nullable=False)
    sender_email: Mapped[str] = mapped_column(String, nullable=False)
    subject: Mapped[str] = mapped_column(String, nullable=False)
    body_html: Mapped[str | None] = mapped_column(Text)
    body_text: Mapped[str | None] = mapped_column(Text)

    status: Mapped[EmailStatus] = mapped_column(
        SAEnum(EmailStatus, name="email_status"),
        nullable=False,
        default=EmailStatus.QUEUED,
    )

    provider_name: Mapped[str | None] = mapped_column(String)
    provider_message_id: Mapped[str | None] = mapped_column(String)

    attempt_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    max_attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=5)
    next_retry_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    last_error: Mapped[str | None] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now, nullable=False
    )

    tracking_events: Mapped[list["TrackingEvent"]] = relationship(
        back_populates="email"
    )


# ── TrackingEvent ─────────────────────────────────────

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