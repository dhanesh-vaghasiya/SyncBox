from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.schemas.user import UserCreate, UserWithApiKeyResponse
from app.services.userService import UserService
from app.db.base import get_db

router = APIRouter()

@router.post("/user", response_model=UserWithApiKeyResponse)
def create_user(request: UserCreate, db: Session = Depends(get_db)):
    user_service = UserService(db)
    created_user = user_service.create_user(request)
    return created_user