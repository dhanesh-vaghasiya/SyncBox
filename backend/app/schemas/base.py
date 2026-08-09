from pydantic import BaseModel, ConfigDict


class ORMBase(BaseModel):
    """Base for any schema that reads directly from a SQLAlchemy model."""
    model_config = ConfigDict(from_attributes=True)