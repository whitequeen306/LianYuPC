#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

ALL_STATES = [
    "idle",
    "running-right",
    "running-left",
    "waving",
    "jumping",
    "failed",
    "waiting",
    "running",
    "review",
]
CANONICAL_BASE_PATH = "references/canonical-base.png"


def read_apikey_file(path: Path) -> tuple[str, str]:
    text = path.read_text(encoding="utf-8")
    url = ""
    key = ""
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("Pets-url") and "：" in line:
            url = line.split("：", 1)[1].strip()
        elif line.startswith("Pets-key") and "：" in line:
            key = line.split("：", 1)[1].strip()
    if not url or not key:
        raise SystemExit("APIKEY.txt missing Pets-url/Pets-key for gpt-image-2")
    return url, key


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_states(raw: str) -> list[str]:
    if raw.strip().lower() == "all":
        return ALL_STATES
    states = [item.strip() for item in raw.split(",") if item.strip()]
    unknown = sorted(set(states) - set(ALL_STATES))
    if unknown:
        raise SystemExit(f"unknown state(s): {', '.join(unknown)}")
    return states


def load_manifest(run_dir: Path) -> dict[str, object]:
    path = run_dir / "imagegen-jobs.json"
    return json.loads(path.read_text(encoding="utf-8"))


def job_list(manifest: dict[str, object]) -> list[dict[str, object]]:
    jobs = manifest.get("jobs")
    if not isinstance(jobs, list):
        raise SystemExit("invalid imagegen-jobs.json: jobs must be a list")
    return [job for job in jobs if isinstance(job, dict)]


def select_jobs(manifest: dict[str, object], *, states: list[str], skip_base: bool, job_ids: list[str]) -> list[dict[str, object]]:
    selected_ids = set(job_ids)
    if not selected_ids:
        if not skip_base:
            selected_ids.add("base")
        selected_ids.update(states)
    selected = [job for job in job_list(manifest) if job.get("id") in selected_ids]
    missing = selected_ids - {str(job.get("id")) for job in selected}
    if missing:
        raise SystemExit(f"unknown job id(s): {', '.join(sorted(missing))}")
    return selected


def path_list(run_dir: Path, job: dict[str, object]) -> list[Path]:
    inputs = job.get("input_images")
    if not isinstance(inputs, list):
        raise SystemExit(f"job {job.get('id')} has invalid input_images")
    paths = []
    for item in inputs:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            raise SystemExit(f"job {job.get('id')} has invalid input image entry")
        path = run_dir / item["path"]
        if not path.is_file():
            raise SystemExit(f"input image for job {job.get('id')} not found: {path}")
        paths.append(path)
    return paths


def preferred_edit_inputs(run_dir: Path, job: dict[str, object]) -> list[Path]:
    """Use the smallest stable grounding set for OpenAI-compatible edits.

    The user's proxy is unstable with multi-image edits. For row jobs, prefer a
    single canonical/base reference image and rely on the row prompt for layout.
    """
    paths = path_list(run_dir, job)
    if str(job.get("id")) == "base":
        return paths[:1]

    preferred = []
    for candidate in paths:
        norm = str(candidate).replace("/", "\\").lower()
        if norm.endswith("references\\canonical-base.png"):
            preferred.append(candidate)
            break
    if not preferred:
        for candidate in paths:
            norm = str(candidate).replace("/", "\\").lower()
            if norm.endswith("decoded\\base.png"):
                preferred.append(candidate)
                break
    if not preferred:
        preferred.append(paths[0])
    return preferred


def run_image_edit(*, base_url: str, api_key: str, model: str, prompt_file: Path, image_paths: list[Path]) -> dict[str, object]:
    url = base_url.rstrip("/") + "/images/edits"
    output_json = prompt_file.parent.parent / "raw" / f"{prompt_file.stem}.response.json"
    output_json.parent.mkdir(parents=True, exist_ok=True)
    last_body = None
    for attempt in range(1, 13):
        command = [
            "curl.exe",
            "-sS",
            "-X",
            "POST",
            url,
            "-H",
            f"Authorization: Bearer {api_key}",
            "-F",
            f"model={model}",
            "-F",
            f"prompt=<{prompt_file}",
            "-F",
            "size=1024x1024",
            "-F",
            "async=false",
            "-F",
            "stream=false",
            "-F",
            "response_format=b64_json",
        ]
        for image_path in image_paths:
            command.extend(["-F", f"image[]=@{image_path}"])
        command.extend(["-o", str(output_json)])
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if result.returncode != 0:
            raise SystemExit(f"image edit curl failed: {result.stderr[:800]}")
        raw_response = output_json.read_text(encoding="utf-8", errors="replace").strip()
        if is_retryable_gateway_response(raw_response):
            print(f"edit attempt {attempt} returned transient gateway response; retrying...", flush=True)
            time.sleep(2)
            continue
        try:
            body = json.loads(raw_response)
        except json.JSONDecodeError as exc:
            raise SystemExit(
                f"image edit returned non-JSON response on attempt {attempt}: "
                f"{raw_response[:500]!r}"
            ) from exc
        if body.get("error"):
            raise SystemExit(json.dumps(body["error"], ensure_ascii=False))
        data = body.get("data")
        if isinstance(data, list) and data:
            return body
        last_body = body
        print(f"edit attempt {attempt} returned async task; retrying...", flush=True)
        time.sleep(2)
    raise SystemExit(f"image edit stayed async after retries: {json.dumps(last_body, ensure_ascii=False)}")


def is_retryable_gateway_response(raw_response: str) -> bool:
    if not raw_response:
        return True
    lowered = raw_response.lower()
    return lowered in {
        "error code: 502",
        "error code: 503",
        "bad gateway",
        "service unavailable",
        "gateway timeout",
    }


def run_image_generation(*, base_url: str, api_key: str, model: str, prompt_file: Path) -> dict[str, object]:
    url = base_url.rstrip("/") + "/images/generations"
    output_json = prompt_file.parent.parent / "raw" / f"{prompt_file.stem}.response.json"
    output_json.parent.mkdir(parents=True, exist_ok=True)
    command = [
        "curl.exe",
        "-sS",
        "-X",
        "POST",
        url,
        "-H",
        f"Authorization: Bearer {api_key}",
        "-F",
        f"model={model}",
        "-F",
        f"prompt=<{prompt_file}",
        "-F",
        "size=1024x1024",
        "-o",
        str(output_json),
    ]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise SystemExit(f"image generation curl failed: {result.stderr[:800]}")
    body = json.loads(output_json.read_text(encoding="utf-8"))
    if body.get("error"):
        raise SystemExit(json.dumps(body["error"], ensure_ascii=False))
    return body


def decode_response(response: dict[str, object], output_image: Path) -> None:
    data = response.get("data")
    if not isinstance(data, list) or not data:
        raise SystemExit("image API response did not contain data[0]")
    first = data[0]
    if isinstance(first, dict) and isinstance(first.get("b64_json"), str):
        output_image.parent.mkdir(parents=True, exist_ok=True)
        output_image.write_bytes(base64.b64decode(first["b64_json"]))
        return
    if isinstance(first, dict) and isinstance(first.get("url"), str):
        image = requests.get(first["url"], timeout=300)
        image.raise_for_status()
        output_image.parent.mkdir(parents=True, exist_ok=True)
        output_image.write_bytes(image.content)
        return
    raise SystemExit("image API response did not contain b64_json or url")


def completed_job_ids(manifest: dict[str, object]) -> set[str]:
    return {
        str(job["id"])
        for job in job_list(manifest)
        if job.get("status") == "complete" and isinstance(job.get("id"), str)
    }


def write_canonical_base(run_dir: Path, manifest: dict[str, object], output_image: Path) -> None:
    canonical = run_dir / CANONICAL_BASE_PATH
    canonical.parent.mkdir(parents=True, exist_ok=True)
    canonical.write_bytes(output_image.read_bytes())
    reference = {
        "path": CANONICAL_BASE_PATH,
        "source_job": "base",
        "sha256": file_sha256(canonical),
    }
    manifest["canonical_identity_reference"] = reference
    request_path = run_dir / "pet_request.json"
    if request_path.exists():
        request = json.loads(request_path.read_text(encoding="utf-8"))
        request["canonical_identity_reference"] = reference
        request_path.write_text(json.dumps(request, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--states", default="all")
    parser.add_argument("--job-id", action="append", default=[])
    parser.add_argument("--skip-base", action="store_true")
    parser.add_argument("--model", default="gpt-image-2")
    parser.add_argument("--apikey-file", default=r"C:\Users\hp\Desktop\APIKEY.txt")
    args = parser.parse_args()

    base_url, api_key = read_apikey_file(Path(args.apikey_file))
    run_dir = Path(args.run_dir).expanduser().resolve()
    manifest_path = run_dir / "imagegen-jobs.json"
    manifest = load_manifest(run_dir)
    jobs = select_jobs(
        manifest,
        states=parse_states(args.states),
        skip_base=args.skip_base,
        job_ids=args.job_id,
    )

    completed = []
    for job in jobs:
        job_id = str(job.get("id"))
        missing_deps = [
            dep for dep in job.get("depends_on", [])
            if isinstance(dep, str) and dep not in completed_job_ids(manifest)
        ]
        if missing_deps:
            raise SystemExit(f"job {job_id} is not ready; missing dependency result(s): {', '.join(missing_deps)}")

        prompt_raw = job.get("prompt_file")
        output_raw = job.get("output_path")
        if not isinstance(prompt_raw, str) or not isinstance(output_raw, str):
            raise SystemExit(f"job {job_id} is missing prompt_file or output_path")
        prompt_file = run_dir / prompt_raw
        output_image = run_dir / output_raw

        print(f"Generating {job_id} with {args.model}", flush=True)
        image_paths = preferred_edit_inputs(run_dir, job)
        if image_paths:
            response = run_image_edit(
                base_url=base_url,
                api_key=api_key,
                model=args.model,
                prompt_file=prompt_file,
                image_paths=image_paths,
            )
        else:
            response = run_image_generation(
                base_url=base_url,
                api_key=api_key,
                model=args.model,
                prompt_file=prompt_file,
            )
        decode_response(response, output_image)

        job["status"] = "complete"
        job["source_path"] = str(output_image)
        job["source_provenance"] = "secondary-fallback-image-api"
        job["source_sha256"] = file_sha256(output_image)
        job["output_sha256"] = file_sha256(output_image)
        job["completed_at"] = datetime.now(timezone.utc).isoformat()
        job["secondary_fallback"] = True
        for key in [
            "last_error",
            "synthetic_test_source",
            "derived_from",
            "mirror_decision",
            "repair_reason",
            "queued_at",
        ]:
            job.pop(key, None)
        if job_id == "base":
            job["canonical_reference_path"] = CANONICAL_BASE_PATH
            write_canonical_base(run_dir, manifest, output_image)
        completed.append({"job_id": job_id, "output": str(output_image)})

    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"ok": True, "completed": completed}, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
