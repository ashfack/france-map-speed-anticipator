#!/usr/bin/env python3
"""Convert a version 2 OSM database to the compact version 3 format."""

import argparse
import gzip
import os
import shutil
import sqlite3
import tempfile
from pathlib import Path


DATABASE_VERSION = 3
BATCH_SIZE = 10000
COORDINATE_SCALE = 1_000_000


def create_schema(connection):
    connection.executescript(
        """
        CREATE TABLE segments (
            segment_id INTEGER PRIMARY KEY,
            way_id INTEGER NOT NULL,
            maxspeed INTEGER NOT NULL,
            oneway INTEGER NOT NULL,
            start_lat_e6 INTEGER NOT NULL,
            start_lon_e6 INTEGER NOT NULL,
            end_lat_e6 INTEGER NOT NULL,
            end_lon_e6 INTEGER NOT NULL
        );
        CREATE VIRTUAL TABLE segment_index
            USING rtree(segment_id, min_lon, max_lon, min_lat, max_lat);
        PRAGMA user_version = 3;
        """
    )


def copy_segments(source, destination):
    cursor = source.execute(
        """
        SELECT segment_id, way_id, maxspeed, oneway,
               start_lat, start_lon, end_lat, end_lon
        FROM segments ORDER BY segment_id
        """
    )
    while rows := cursor.fetchmany(BATCH_SIZE):
        segments = []
        index_rows = []
        for (
            segment_id,
            way_id,
            maxspeed,
            oneway,
            start_lat,
            start_lon,
            end_lat,
            end_lon,
        ) in rows:
            start_lat_e6 = round(start_lat * COORDINATE_SCALE)
            start_lon_e6 = round(start_lon * COORDINATE_SCALE)
            end_lat_e6 = round(end_lat * COORDINATE_SCALE)
            end_lon_e6 = round(end_lon * COORDINATE_SCALE)
            segments.append(
                (
                    segment_id,
                    way_id,
                    maxspeed,
                    oneway,
                    start_lat_e6,
                    start_lon_e6,
                    end_lat_e6,
                    end_lon_e6,
                )
            )
            start_lat, start_lon = (
                start_lat_e6 / COORDINATE_SCALE,
                start_lon_e6 / COORDINATE_SCALE,
            )
            end_lat, end_lon = (
                end_lat_e6 / COORDINATE_SCALE,
                end_lon_e6 / COORDINATE_SCALE,
            )
            index_rows.append(
                (
                    segment_id,
                    min(start_lon, end_lon),
                    max(start_lon, end_lon),
                    min(start_lat, end_lat),
                    max(start_lat, end_lat),
                )
            )

        destination.executemany(
            """
            INSERT INTO segments
                (segment_id, way_id, maxspeed, oneway,
                 start_lat_e6, start_lon_e6, end_lat_e6, end_lon_e6)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            segments,
        )
        destination.executemany(
            """
            INSERT INTO segment_index
                (segment_id, min_lon, max_lon, min_lat, max_lat)
            VALUES (?, ?, ?, ?, ?)
            """,
            index_rows,
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Version 2 region_osm.sqlite")
    parser.add_argument("output", type=Path, help="Version 3 gzip-compressed database")
    parser.add_argument("--replace", action="store_true", help="Replace existing output")
    args = parser.parse_args()

    if not args.input.is_file():
        parser.error(f"Input database not found: {args.input}")
    if args.input.resolve() == args.output.resolve():
        parser.error("Input and output must be different files")
    if args.output.exists() and not args.replace:
        parser.error(f"Output already exists; pass --replace to overwrite: {args.output}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    raw_fd, raw_path = tempfile.mkstemp(
        prefix="osm-", suffix=".sqlite", dir=args.output.parent
    )
    os.close(raw_fd)
    compressed_fd, compressed_path = tempfile.mkstemp(
        prefix="osm-", suffix=".gz", dir=args.output.parent
    )
    os.close(compressed_fd)

    try:
        with sqlite3.connect(f"file:{args.input.resolve()}?mode=ro", uri=True) as source:
            if source.execute("PRAGMA user_version").fetchone()[0] != 2:
                parser.error("Input database must have SQLite user_version 2")
            with sqlite3.connect(raw_path) as destination:
                create_schema(destination)
                copy_segments(source, destination)
                destination.execute("PRAGMA optimize")

        with sqlite3.connect(f"file:{raw_path}?mode=ro", uri=True) as check:
            segment_count = check.execute("SELECT count(*) FROM segments").fetchone()[0]
            index_count = check.execute("SELECT count(*) FROM segment_index").fetchone()[0]
            if segment_count == 0 or segment_count != index_count:
                raise RuntimeError("Generated segment table and spatial index do not match")

        with open(raw_path, "rb") as source, gzip.open(
            compressed_path, "wb", compresslevel=6
        ) as destination:
            shutil.copyfileobj(source, destination)
        os.replace(compressed_path, args.output)
    finally:
        Path(raw_path).unlink(missing_ok=True)
        Path(compressed_path).unlink(missing_ok=True)

    version_file = args.output.parent / "region_osm.version"
    version_file.write_text(f"{DATABASE_VERSION}\n", encoding="ascii")
    print(
        f"Generated {args.output}: {segment_count:,} segments, "
        f"{args.output.stat().st_size / (1024 * 1024):.1f} MiB"
    )
    print(f"Generated {version_file}")


if __name__ == "__main__":
    main()
