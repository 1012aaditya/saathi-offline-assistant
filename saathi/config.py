"""Typed settings loaded from .env, plus app alias -> package maps."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = ""
    openai_model: str = "gpt-5-nano"  # cheapest vision + strict-JSON model tested in M0
    sarvam_api_key: str = ""
    adb_serial: str | None = None
    portal_service: str = ""
    talkback_service: str = ""
    max_amount_inr: int = 10
    vision_apps: str = "com.phonepe.app"


def get_settings() -> Settings:
    return Settings()
