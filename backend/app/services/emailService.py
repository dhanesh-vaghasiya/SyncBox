import uuid
from app.schemas.email import EmailCreate
from app.models import Email


class EmailService:
    def __init__(self, db_session):
        self.db = db_session

    def createEmail(self, request: EmailCreate, user_id: uuid.UUID):
        email = Email(**request.model_dump(), user_id=user_id)
        self.db.add(email)
        self.db.commit()
        self.db.refresh(email)

        return email
