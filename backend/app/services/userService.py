
import secrets
from app.schemas.user import UserCreate
from app.models import User

class UserService:
    def __init__(self, db):
        self.db = db

    def create_user(self, request: UserCreate) -> User:
        # Generate a secure API key
        api_key = "mr_" + secrets.token_urlsafe(32)
        
        user_data = request.model_dump()
        if user_data.get("webhook_url"):
            user_data["webhook_url"] = str(user_data["webhook_url"])
            
        user = User(**user_data, api_key=api_key)
        self.db.add(user)
        self.db.commit()
        self.db.refresh(user)
        return user