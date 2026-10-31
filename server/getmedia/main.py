"""uvicorn getmedia.main:create --factory --host 127.0.0.1 --port 8700"""
from fastapi import FastAPI

from .api import create_app
from .config import load_settings


def create() -> FastAPI:
    return create_app(load_settings(), start_workers=True)
