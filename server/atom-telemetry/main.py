import hmac
import os
import sqlite3
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Literal
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

DB_PATH = os.getenv("ATOM_TELEMETRY_DB", "/data/atom-telemetry.sqlite3")
ADMIN_TOKEN = os.getenv("ATOM_TELEMETRY_ADMIN_TOKEN", "")

app = FastAPI(
    title="Atom Telemetry API",
    version="1.0.0",
    docs_url=None,
    redoc_url=None,
)


class TelemetryEvent(BaseModel):
    event_id: UUID
    install_id: UUID
    event_type: Literal["install", "active"]
    app_version: str = Field(min_length=1, max_length=40)
    occurred_at: datetime


class EventBatch(BaseModel):
    events: list[TelemetryEvent] = Field(min_length=1, max_length=100)


def connect_db():
    Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=20)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA busy_timeout=20000")
    return connection


def init_db():
    with connect_db() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS processed_events (
                event_id TEXT PRIMARY KEY
            );

            CREATE TABLE IF NOT EXISTS installations (
                install_id TEXT PRIMARY KEY,
                installed_at TEXT NOT NULL,
                app_version TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS daily_activity (
                install_id TEXT NOT NULL,
                activity_date TEXT NOT NULL,
                PRIMARY KEY (install_id, activity_date)
            );

            CREATE INDEX IF NOT EXISTS idx_daily_activity_date
                ON daily_activity(activity_date);
            CREATE INDEX IF NOT EXISTS idx_installations_date
                ON installations(installed_at);
            """
        )


@app.on_event("startup")
def startup():
    init_db()


def normalized_event_time(value: datetime) -> datetime:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    value = value.astimezone(timezone.utc)
    now = datetime.now(timezone.utc)
    # Prevent broken device clocks from creating arbitrary historical/future activity.
    if value < now - timedelta(days=30) or value > now + timedelta(minutes=5):
        return now
    return value


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/v1/events/batch")
def ingest(batch: EventBatch):
    accepted = 0
    duplicates = 0

    with connect_db() as db:
        db.execute("BEGIN IMMEDIATE")
        for event in batch.events:
            event_id = str(event.event_id)
            install_id = str(event.install_id)
            cursor = db.execute(
                "INSERT OR IGNORE INTO processed_events(event_id) VALUES (?)",
                (event_id,),
            )
            if cursor.rowcount == 0:
                duplicates += 1
                continue

            event_time = normalized_event_time(event.occurred_at)
            timestamp = event_time.isoformat()
            activity_date = event_time.date().isoformat()

            if event.event_type == "install":
                db.execute(
                    """
                    INSERT OR IGNORE INTO installations(install_id, installed_at, app_version)
                    VALUES (?, ?, ?)
                    """,
                    (install_id, timestamp, event.app_version),
                )

            db.execute(
                """
                INSERT OR IGNORE INTO daily_activity(install_id, activity_date)
                VALUES (?, ?)
                """,
                (install_id, activity_date),
            )
            accepted += 1

    return {"accepted": accepted, "duplicates": duplicates}


def require_admin(token: str | None):
    if not ADMIN_TOKEN:
        raise HTTPException(status_code=503, detail="Admin API is not configured")
    if token is None or not hmac.compare_digest(token, ADMIN_TOKEN):
        raise HTTPException(status_code=401, detail="Unauthorized")


@app.get("/v1/admin/stats")
def get_stats(x_admin_token: str | None = Header(default=None)):
    require_admin(x_admin_token)
    today = datetime.now(timezone.utc).date()
    month_start = today - timedelta(days=29)
    week_start = today - timedelta(days=6)

    with connect_db() as db:
        total_installs = db.execute(
            "SELECT COUNT(*) FROM installations"
        ).fetchone()[0]
        new_installs_today = db.execute(
            "SELECT COUNT(*) FROM installations WHERE substr(installed_at, 1, 10) = ?",
            (today.isoformat(),),
        ).fetchone()[0]
        dau = db.execute(
            "SELECT COUNT(DISTINCT install_id) FROM daily_activity WHERE activity_date = ?",
            (today.isoformat(),),
        ).fetchone()[0]
        mau = db.execute(
            "SELECT COUNT(DISTINCT install_id) FROM daily_activity WHERE activity_date >= ?",
            (month_start.isoformat(),),
        ).fetchone()[0]

        daily_rows = db.execute(
            """
            SELECT activity_date, COUNT(DISTINCT install_id) AS active_users
            FROM daily_activity
            WHERE activity_date >= ?
            GROUP BY activity_date
            ORDER BY activity_date
            """,
            (week_start.isoformat(),),
        ).fetchall()

    return {
        "as_of_utc": datetime.now(timezone.utc).isoformat(),
        "total_installations": total_installs,
        "new_installs_today": new_installs_today,
        "daily_active_users": dau,
        "monthly_active_users_30d": mau,
        "daily_active_users_last_7_days": [
            {"date": row["activity_date"], "users": row["active_users"]}
            for row in daily_rows
        ],
    }
