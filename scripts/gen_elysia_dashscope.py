#!/usr/bin/env python3
"""DashScope (Tongyi Wanxiang) image generation adapter for hatch-pet-v1.

Replaces the OpenAI image generation path with DashScope's text-to-image API.
Uses the same job manifest format as generate_pet_images.py.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import shutil
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import requests

ALL_STATES = [
    "idle", "running-right", "running-left", "waving", "jumping",
    "failed", "waiting", "running", "review",
]
CANONICAL_BASE_PATH = "references/canonical-base.png"
DASHSCOPE_T2I_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis"
DASHSCOPE_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}"


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, v = s.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def submit_t2i_task(api_key: str, prompt: str, size: str = "1024*1024", n: int = 1) -> str:
    payload = {
        "model": "wanx2.1-t2i-turbo",
        "input": {"prompt": prompt},
        "parameters": {"size": size, "n": n},
    }
    resp = requests.post(
        DASHSCOPE_T2I_URL,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "X-DashScope-Async": "enable",
        },
        json=payload,
        timeout=120,
    )
    body = resp.json()
    task_id = body.get("output", {}).get("task_id")
    if not task_id:
        raise RuntimeError(f"no task_id in response: {body}")
    return task_id


def poll_task(api_key: str, task_id: str, timeout: int = 300) -> str:
    url = DASHSCOPE_TASK_URL.format(task_id=task_id)
    deadline = time.time() + timeout
    while time.time() < deadline:
        resp = requests.get(
            url,
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=60,
        )
        body = resp.json()
        status = body.get("output", {}).get("task_status", "")
        if status == "SUCCEEDED":
            results = body.get("output", {}).get("results", [])
            if results and results[0].get("url"):
                return results[0]["url"]
            raise RuntimeError(f"task succeeded but no image url: {body}")
        if status == "FAILED":
            raise RuntimeError(f"task failed: {body}")
        print(f"  task {task_id[:12]}... status={status}, waiting...")
        time.sleep(5)
    raise RuntimeError(f"task {task_id} timed out after {timeout}s")


def download_image(url: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url, timeout=120) as resp:
        output.write_bytes(resp.read())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--states", default="all")
    parser.add_argument("--skip-base", action="store_true")
    parser.add_argument("--job-id", action="append", default=[])
    parser.add_argument("--size", default="1024*1024")
    args = parser.parse_args()

    load_dotenv(Path(r"C:\Users\hp\Desktop\LianYu-PC\.env"))
    api_key = os.environ.get("DASHSCOPE_API_KEY")
    if not api_key:
        raise SystemExit("DASHSCOPE_API_KEY not set")

    run_dir = Path(args.run_dir).resolve()
    manifest_path = run_dir / "imagegen-jobs.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    jobs = manifest.get("jobs", [])

    states = ALL_STATES if args.states.strip().lower() == "all" else [s.strip() for s in args.states.split(",")]
    selected_ids = set(args.job_id)
    if not selected_ids:
        if not args.skip_base:
            selected_ids.add("base")
        selected_ids.update(states)

    selected = [j for j in jobs if j.get("id") in selected_ids]
    missing = selected_ids - {j["id"] for j in selected}
    if missing:
        raise SystemExit(f"unknown job ids: {sorted(missing)}")

    completed = []
    for job in selected:
        jid = job["id"]
        if job.get("status") == "complete":
            print(f"[{jid}] already complete, skipping")
            continue

        prompt_file = run_dir / job["prompt_file"]
        prompt = prompt_file.read_text(encoding="utf-8").strip()

        print(f"[{jid}] submitting to DashScope...")
        try:
            task_id = submit_t2i_task(api_key, prompt, args.size)
            print(f"  task_id={task_id}")
            image_url = poll_task(api_key, task_id, timeout=600)
            print(f"  image_url={image_url[:80]}...")
            output_path = run_dir / job["output_path"]
            download_image(image_url, output_path)
            print(f"  downloaded -> {output_path}")
        except Exception as e:
            print(f"  FAILED: {e}")
            continue

        job["status"] = "complete"
        job["source_path"] = str(output_path)
        job["source_provenance"] = "dashscope-wanx-t2i"
        job["source_sha256"] = file_sha256(output_path)
        job["output_sha256"] = file_sha256(output_path)
        job["completed_at"] = datetime.now(timezone.utc).isoformat()

        if jid == "base":
            canonical = run_dir / CANONICAL_BASE_PATH
            canonical.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(output_path, canonical)
            ref = {"path": CANONICAL_BASE_PATH, "source_job": "base", "sha256": file_sha256(canonical)}
            manifest["canonical_identity_reference"] = ref
            req_path = run_dir / "pet_request.json"
            if req_path.exists():
                req = json.loads(req_path.read_text(encoding="utf-8"))
                req["canonical_identity_reference"] = ref
                req_path.write_text(json.dumps(req, indent=2) + "\n", encoding="utf-8")

        completed.append({"job_id": jid, "output": str(output_path)})

    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"ok": True, "completed": completed}, indent=2))


if __name__ == "__main__":
    main()
