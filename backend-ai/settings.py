"""Machine-specific settings come from environment variables or a local .env."""

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).with_name('.env'), override=False)


def database_config():
    return {
        'host': os.getenv('SMARTLOCK_DB_HOST', 'localhost'),
        'port': int(os.getenv('SMARTLOCK_DB_PORT', '3306')),
        'user': os.getenv('SMARTLOCK_DB_USER', 'root'),
        'password': os.getenv('SMARTLOCK_DB_PASSWORD', ''),
        'database': os.getenv('SMARTLOCK_DB_NAME', 'btl_iot'),
    }
