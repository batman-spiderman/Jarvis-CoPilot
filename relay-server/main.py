"""
Jarvis relay server.

Holds all third-party API keys (NVIDIA, Gemini, Groq) and the Backblaze B2
application key server-side. The Android client only ever calls this server
with a shared-secret bearer token — it never sees a provider key or a B2 key.

All user content — photos, voice-sample audio, and voice-sample transcripts —
is written to Backblaze B2 by this server, never stored locally beyond the
lifetime of a single request, and never touched directly by the Android app.

Run locally for testing:
    pip install -r requirements.txt
    uvicorn main:app --reload

Deploy: Render / Fly.io / Railway free-hobby tier is enough for one user's traffic.
"""

import itertools
import os
import time
from typing import Optional

import boto3
import httpx
from botocore.client import Config
from botocore.exceptions import ClientError
from fastapi import FastAPI, Header, HTTPException, UploadFile, Form, Query
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel

app = FastAPI(title="Jarvis Relay")

# --------------------------------------------------------------------------
# CREDENTIAL PLACEHOLDERS — set these as real environment variables on your
# deploy host (Render/Fly.io dashboard, or a local .env loaded via
# python-dotenv). NEVER commit real values — see .env.example.
# --------------------------------------------------------------------------
APP_SHARED_SECRET = os.environ.get("APP_SHARED_SECRET", "FILL_IN_SHARED_SECRET")

PROVIDERS = [
    {
        "name": "nvidia",
        "url": "https://integrate.api.nvidia.com/v1/chat/completions",
        "key": os.environ.get("NVIDIA_KEY", "FILL_IN_NVIDIA_KEY"),
        "model": "meta/llama3-70b-instruct",
    },
    {
        "name": "gemini",
        "url": "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent",
        "key": os.environ.get("GEMINI_KEY", "FILL_IN_GEMINI_KEY"),
        "model": None,
    },
    {
        "name": "groq",
        "url": "https://api.groq.com/openai/v1/chat/completions",
        "key": os.environ.get("GROQ_KEY", "FILL_IN_GROQ_KEY"),
        "model": "llama-3.3-70b-versatile",
    },
]
_provider_cycle = itertools.cycle(PROVIDERS)

# --------------------------------------------------------------------------
# Backblaze B2 (S3-compatible API). Get these from the B2 console:
# Application Keys → Add a New Application Key (scope it to one bucket).
# B2_ENDPOINT_URL is bucket-region-specific, e.g.
# "https://s3.us-west-004.backblazeb2.com" — shown next to your bucket name
# in the B2 console.
# --------------------------------------------------------------------------
B2_KEY_ID = os.environ.get("B2_KEY_ID", "FILL_IN_B2_KEY_ID")
B2_APPLICATION_KEY = os.environ.get("B2_APPLICATION_KEY", "FILL_IN_B2_APPLICATION_KEY")
B2_BUCKET_NAME = os.environ.get("B2_BUCKET_NAME", "FILL_IN_B2_BUCKET_NAME")
B2_ENDPOINT_URL = os.environ.get("B2_ENDPOINT_URL", "FILL_IN_B2_ENDPOINT_URL")

b2_client = boto3.client(
    "s3",
    endpoint_url=B2_ENDPOINT_URL,
    aws_access_key_id=B2_KEY_ID,
    aws_secret_access_key=B2_APPLICATION_KEY,
    config=Config(signature_version="s3v4"),
)

# In-memory index of voice-sample keys + training status, standing in for a
# real DB (Postgres/SQLite). The audio/transcript bytes themselves live in
# B2 either way — this dict only tracks which B2 keys exist and whether
# they've been marked used-in-training. Swap for a persistent store before
# relying on this beyond local testing (restarting the server forgets which
# samples were already marked trained, though the B2 objects themselves are
# untouched).
VOICE_SAMPLES: dict[str, dict] = {}


def verify_auth(authorization: Optional[str]):
    if authorization != f"Bearer {APP_SHARED_SECRET}":
        raise HTTPException(status_code=401, detail="Unauthorized")


def _b2_put(key: str, data: bytes, content_type: str):
    b2_client.put_object(Bucket=B2_BUCKET_NAME, Key=key, Body=data, ContentType=content_type)


def _b2_list(prefix: str) -> list[str]:
    keys = []
    paginator = b2_client.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=B2_BUCKET_NAME, Prefix=prefix):
        for obj in page.get("Contents", []):
            keys.append(obj["Key"])
    return keys


def _b2_delete(key: str):
    b2_client.delete_object(Bucket=B2_BUCKET_NAME, Key=key)


def _b2_get_text(key: str) -> Optional[str]:
    try:
        resp = b2_client.get_object(Bucket=B2_BUCKET_NAME, Key=key)
        return resp["Body"].read().decode("utf-8")
    except ClientError:
        return None


class ChatRequest(BaseModel):
    query: str


@app.post("/chat")
async def chat(payload: ChatRequest, authorization: str = Header(default="")):
    verify_auth(authorization)

    last_error = None
    for _ in range(len(PROVIDERS)):
        provider = next(_provider_cycle)
        try:
            async with httpx.AsyncClient(timeout=15) as client:
                if provider["name"] == "gemini":
                    resp = await client.post(
                        f"{provider['url']}?key={provider['key']}",
                        json={"contents": [{"parts": [{"text": payload.query}]}]},
                    )
                    if resp.status_code == 429:
                        continue
                    resp.raise_for_status()
                    text = resp.json()["candidates"][0]["content"]["parts"][0]["text"]
                    return {"text": text}
                else:
                    resp = await client.post(
                        provider["url"],
                        headers={"Authorization": f"Bearer {provider['key']}"},
                        json={
                            "model": provider["model"],
                            "messages": [{"role": "user", "content": payload.query}],
                        },
                    )
                    if resp.status_code == 429:
                        continue
                    resp.raise_for_status()
                    text = resp.json()["choices"][0]["message"]["content"]
                    return {"text": text}
        except Exception as e:
            last_error = e
            continue

    raise HTTPException(status_code=503, detail=f"All providers failed: {last_error}")


# --------------------------------------------------------------------------
# Voice samples — audio (.wav) and transcript (.txt) both stored in B2,
# under voice/{deviceTag}/{sample_id}.{ext}
# --------------------------------------------------------------------------
@app.post("/voice-samples")
async def upload_voice_sample(
    audio: UploadFile,
    transcript: str = Form(...),
    deviceTag: str = Form(...),
    timestamp: str = Form(...),
    authorization: str = Header(default=""),
):
    verify_auth(authorization)

    sample_id = f"{deviceTag}_{timestamp}"
    audio_bytes = await audio.read()
    audio_key = f"voice/{deviceTag}/{sample_id}.wav"
    transcript_key = f"voice/{deviceTag}/{sample_id}.txt"

    try:
        await run_in_threadpool(_b2_put, audio_key, audio_bytes, "audio/wav")
        await run_in_threadpool(_b2_put, transcript_key, transcript.encode("utf-8"), "text/plain")
    except ClientError as e:
        raise HTTPException(status_code=502, detail=f"B2 upload failed: {e}")

    VOICE_SAMPLES[sample_id] = {
        "audioKey": audio_key,
        "transcriptKey": transcript_key,
        "deviceTag": deviceTag,
        "timestamp": timestamp,
        "usedInTraining": False,
    }
    return {"id": sample_id, "status": "stored"}


@app.get("/voice-samples/count")
async def count_voice_samples(authorization: str = Header(default="")):
    verify_auth(authorization)
    return {"count": len(VOICE_SAMPLES), "untrained": sum(
        1 for s in VOICE_SAMPLES.values() if not s["usedInTraining"]
    )}


@app.post("/voice-samples/mark-trained")
async def mark_trained(sample_ids: list[str], authorization: str = Header(default="")):
    verify_auth(authorization)
    for sid in sample_ids:
        if sid in VOICE_SAMPLES:
            VOICE_SAMPLES[sid]["usedInTraining"] = True
    return {"updated": len(sample_ids)}


@app.delete("/voice-samples")
async def clear_voice_samples(authorization: str = Header(default="")):
    verify_auth(authorization)
    count = len(VOICE_SAMPLES)
    try:
        for sample in VOICE_SAMPLES.values():
            await run_in_threadpool(_b2_delete, sample["audioKey"])
            await run_in_threadpool(_b2_delete, sample["transcriptKey"])
    except ClientError as e:
        raise HTTPException(status_code=502, detail=f"B2 delete failed: {e}")
    VOICE_SAMPLES.clear()
    return {"deleted": count}


# --------------------------------------------------------------------------
# Photos — Cloud Backup / Media Vault. Stored under photos/{deviceTag}/{filename}
# B2 is the sole source of truth here (no local index) — list/delete read
# straight from the bucket.
# --------------------------------------------------------------------------
@app.post("/photos")
async def upload_photo(
    photo: UploadFile,
    deviceTag: str = Form(...),
    filename: str = Form(...),
    authorization: str = Header(default=""),
):
    verify_auth(authorization)

    photo_bytes = await photo.read()
    key = f"photos/{deviceTag}/{filename}"
    try:
        await run_in_threadpool(_b2_put, key, photo_bytes, "image/jpeg")
    except ClientError as e:
        raise HTTPException(status_code=502, detail=f"B2 upload failed: {e}")

    return {"key": key, "status": "stored"}


@app.get("/photos")
async def list_photos(deviceTag: str = Query(...), authorization: str = Header(default="")):
    verify_auth(authorization)
    prefix = f"photos/{deviceTag}/"
    try:
        keys = await run_in_threadpool(_b2_list, prefix)
    except ClientError as e:
        raise HTTPException(status_code=502, detail=f"B2 list failed: {e}")
    return {"filenames": [k[len(prefix):] for k in keys]}


@app.delete("/photos/{filename}")
async def delete_photo(filename: str, deviceTag: str = Query(...), authorization: str = Header(default="")):
    verify_auth(authorization)
    key = f"photos/{deviceTag}/{filename}"
    try:
        await run_in_threadpool(_b2_delete, key)
    except ClientError as e:
        raise HTTPException(status_code=502, detail=f"B2 delete failed: {e}")
    return {"deleted": filename}


@app.get("/health")
async def health():
    return {"status": "ok", "time": time.time()}
