"""建 `memory_vectors_v3` 集合（双写用）。

为什么单独一个脚本：建集合是有破坏性的运维动作（维度写错要重建），不该在 FastAPI
启动时顺手做。跑法：

    python -m scripts.init_milvus_v3            # 不存在才建
    python -m scripts.init_milvus_v3 --recreate # 删掉重建（向量可重算，安全）

v2 与 v3 的差别只有三个标量：layer / status / event_time。有了 status，检索就能
只捞 active 的行，不用把 superseded 的旧摘要也召回再回 MySQL 过滤。
"""

from __future__ import annotations

import argparse
import sys

from app.config import get_settings


def build_schema(dim: int):
    from pymilvus import DataType, MilvusClient

    schema = MilvusClient.create_schema(auto_id=True, enable_dynamic_field=False)
    schema.add_field("id", DataType.INT64, is_primary=True)
    schema.add_field("character_id", DataType.INT64)
    schema.add_field("user_id", DataType.INT64)
    schema.add_field("memory_id", DataType.INT64)
    schema.add_field("summary", DataType.VARCHAR, max_length=512)
    schema.add_field("memory_type", DataType.VARCHAR, max_length=32)
    schema.add_field("layer", DataType.VARCHAR, max_length=32)
    schema.add_field("status", DataType.VARCHAR, max_length=16)
    schema.add_field("event_time", DataType.INT64)
    schema.add_field("vector", DataType.FLOAT_VECTOR, dim=dim)
    return schema


def build_index_params():
    from pymilvus import MilvusClient

    index = MilvusClient.prepare_index_params()
    index.add_index(
        field_name="vector",
        index_type="HNSW",
        metric_type="COSINE",
        params={"M": 16, "efConstruction": 200},
    )
    return index


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="create memory_vectors_v3")
    parser.add_argument("--recreate", action="store_true", help="drop the collection first")
    args = parser.parse_args(argv)

    settings = get_settings()
    from pymilvus import MilvusClient

    client = MilvusClient(
        uri=f"http://{settings.milvus_host}:{settings.milvus_port}",
        timeout=settings.milvus_timeout_seconds,
    )
    name = settings.milvus_v3_collection
    if client.has_collection(name):
        if not args.recreate:
            print(f"{name} already exists; pass --recreate to rebuild")
            return 0
        client.drop_collection(name)
        print(f"dropped {name}")

    client.create_collection(
        collection_name=name,
        schema=build_schema(settings.embedding_dim),
        index_params=build_index_params(),
    )
    client.load_collection(name)
    print(f"created {name} (dim={settings.embedding_dim}, COSINE/HNSW)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
