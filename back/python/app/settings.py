from pydantic import BaseModel, Field


class Settings(BaseModel):
    connect_timeout: float = Field(default=5.0, gt=0)
    read_timeout: float = Field(default=30.0, gt=0)
    callback_attempts: int = Field(default=3, ge=1, le=10)
    callback_backoff_seconds: float = Field(default=0.05, ge=0)


settings = Settings()
