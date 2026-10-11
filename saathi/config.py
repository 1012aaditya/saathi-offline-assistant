"""Typed settings loaded from .env, plus app alias -> package maps."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = ""
    openai_model: str = "gpt-5-nano"  # cheapest vision + strict-JSON model tested in M0
    openai_base_url: str | None = None  # e.g. http://localhost:11434/v1 for the Ollama backup
    sarvam_api_key: str = ""
    adb_serial: str | None = None
    portal_service: str = ""
    talkback_service: str = ""
    max_amount_inr: int = 10
    vision_apps: str = "com.phonepe.app"

    @property
    def vision_app_set(self) -> set[str]:
        return {p.strip() for p in self.vision_apps.split(",") if p.strip()}


def get_settings() -> Settings:
    return Settings()


WHATSAPP = "com.whatsapp"
GPAY = "com.google.android.apps.nbu.paisa.user"
BLINKIT = "com.grofers.customerapp"
SETTINGS_APP = "com.android.settings"

# Spoken / typed app names -> package. Keys are lowercase.
APP_ALIASES: dict[str, str] = {
    "whatsapp": WHATSAPP,
    "व्हाट्सएप": WHATSAPP,
    "gpay": GPAY,
    "google pay": GPAY,
    "जीपे": GPAY,
    "blinkit": BLINKIT,
    "ब्लिंकिट": BLINKIT,
    "settings": SETTINGS_APP,
}

PAYMENT_APPS = {
    GPAY,
    "com.phonepe.app",
    "net.one97.paytm",
    "in.org.npci.upiapp",  # BHIM
}
ORDER_APPS = {BLINKIT}
