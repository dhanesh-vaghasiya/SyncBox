import uuid
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
from app.schemas.email import EmailResponse, EmailCreate
from app.services.emailService import EmailService
from app.db.base import get_db

router = APIRouter()

@router.post("/deliveries", response_model=EmailResponse)
def send_delivery(request: EmailCreate, db: Session = Depends(get_db)):
    email_service = EmailService(db_session=db)
    
    # In a real scenario, you'd get the user_id from the authenticated user
    # For now, we will pass a placeholder UUID to satisfy the signature
    placeholder_user_id = uuid.uuid4()
    
    email = email_service.createEmail(request, user_id="b9627dda-6a8c-4e0a-a23c-d52e720fcf71")
    return email