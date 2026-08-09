
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.api.endpoints import emails, user

app = FastAPI(
    title="My FastAPI Project",
    description="A basic structure for a Python FastAPI backend.",
    version="0.1.0",
)

app.include_router(emails.router, tags=["Email"])
app.include_router(user.router, tags=["User"])

# Set up CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], # Change this to your frontend URL in production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/")
def read_root():
    return {"message": "Welcome to the API!"}
