#!/usr/bin/env python3
"""Generate the Android road-speed database from a Geofabrik OSM PBF extract."""

import argparse
import gzip
import os
import re
import shutil
import sqlite3
import tempfile
from pathlib import Path


DATABASE_VERSION = 3
SPEED_PATTERN = re.compile(r"^\s*(\d{1,3})(?:[.,]\d+)?\s*(mph|km/?h|kmh)?", re.IGNORECASE)
FLUSH_SIZE = 5000


def parse_maxspeed(value):
    """Return a usable integer speed in km/h, or None for symbolic values."""
    if not value:
        return None

    match = SPEED_PATTERN.match(value.split(";", 1)[0])
    if not match:
        return None

    speed = int(match.group(1))
    unit = (match.group(2) or "km/h").lower()
    if unit == "mph":
        speed = round(speed * 1.609344)
    return speed if 0 < speed <= 250 else None


def parse_oneway(tags):
    value = tags.get("oneway", "").strip().lower()
    if value in {"yes", "true", "1"}:
        return 1
    if value == "-1":
        return -1
    if value in {"no", "false", "0"}:
        return 0
    if value == "reversible":
        return 0
    if value == "":
        if tags.get("junction", "").lower() == "roundabout":
            return 1
        if tags.get("highway") in {"motorway", "motorway_link"}:
            return 1
    return 0


def create_schema(connection):
    cursor = connection.cursor()
    cursor.executescript(
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
    return cursor


class SpeedLimitHandler:
    def __init__(self, cursor):
        self.cursor = cursor
        self.segments_batch = []
        self.next_segment_id = 1

    def way(self, way):
        if "highway" not in way.tags or "maxspeed" not in way.tags:
            return

        maxspeed = parse_maxspeed(way.tags.get("maxspeed"))
        if maxspeed is None:
            return

        valid_nodes = []
        segments = []
        previous_node = None
        for node in way.nodes:
            if node.location.valid():
                point = (node.location.lat, node.location.lon)
                valid_nodes.append(point)
                if previous_node is not None and previous_node != point:
                    segments.append((previous_node, point))
                previous_node = point
            else:
                previous_node = None

        if not valid_nodes:
            return

        oneway = parse_oneway(way.tags)
        for start, end in segments:
            start_lat, start_lon = start
            end_lat, end_lon = end
            self.segments_batch.append(
                (
                    self.next_segment_id,
                    way.id,
                    maxspeed,
                    oneway,
                    round(start_lat * 1_000_000),
                    round(start_lon * 1_000_000),
                    round(end_lat * 1_000_000),
                    round(end_lon * 1_000_000),
                )
            )
            self.next_segment_id += 1

        if len(self.segments_batch) >= FLUSH_SIZE:
            self.flush()

    def flush(self):
        index_rows = []
        self.cursor.executemany(
            """
            INSERT INTO segments
                (segment_id, way_id, maxspeed, oneway,
                 start_lat_e6, start_lon_e6, end_lat_e6, end_lon_e6)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            self.segments_batch,
        )
        for segment in self.segments_batch:
            segment_id, _, _, _, start_lat, start_lon, end_lat, end_lon = segment
            start_lat, start_lon = start_lat / 1_000_000, start_lon / 1_000_000
            end_lat, end_lon = end_lat / 1_000_000, end_lon / 1_000_000
            index_rows.append(
                (
                    segment_id,
                    min(start_lon, end_lon),
                    max(start_lon, end_lon),
                    min(start_lat, end_lat),
                    max(start_lat, end_lat),
                )
            )
        self.cursor.executemany(
            """
            INSERT INTO segment_index
                (segment_id, min_lon, max_lon, min_lat, max_lat)
            VALUES (?, ?, ?, ?, ?)
            """,
            index_rows,
        )
        self.segments_batch.clear()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pbf", type=Path, help="Input .osm.pbf file")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/region_osm.sqlite.gz"),
        help="Output gzip-compressed SQLite database",
    )
    parser.add_argument(
        "--replace",
        action="store_true",
        help="Replace an existing output database",
    )
    args = parser.parse_args()

    if not args.pbf.is_file():
        parser.error(f"PBF file not found: {args.pbf}")
    try:
        import osmium
    except ImportError as error:
        parser.error(f"Install pyosmium first (python -m pip install osmium): {error}")

    output = args.output
    if output.exists() and not args.replace:
        parser.error(f"Output already exists; pass --replace to overwrite: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    raw_fd, raw_path = tempfile.mkstemp(
        prefix="osm-", suffix=".sqlite", dir=output.parent
    )
    os.close(raw_fd)
    compressed_fd, compressed_path = tempfile.mkstemp(
        prefix="osm-", suffix=".gz", dir=output.parent
    )
    os.close(compressed_fd)

    try:
        with sqlite3.connect(raw_path) as connection:
            cursor = create_schema(connection)

            class OsmiumSpeedLimitHandler(osmium.SimpleHandler):
                def __init__(self, db_cursor):
                    super().__init__()
                    self.processor = SpeedLimitHandler(db_cursor)

                def way(self, way):
                    self.processor.way(way)

                def flush(self):
                    self.processor.flush()

            handler = OsmiumSpeedLimitHandler(cursor)
            handler.apply_file(str(args.pbf), locations=True)
            handler.flush()

            segment_count = connection.execute(
                "SELECT count(*) FROM segments"
            ).fetchone()[0]
            index_count = connection.execute(
                "SELECT count(*) FROM segment_index"
            ).fetchone()[0]
            if segment_count == 0 or segment_count != index_count:
                raise RuntimeError("Generated segment table and spatial index do not match")

        with open(raw_path, "rb") as source, gzip.open(
            compressed_path, "wb", compresslevel=6
        ) as destination:
            shutil.copyfileobj(source, destination)
        os.replace(compressed_path, output)
    except Exception:
        raise
    finally:
        Path(raw_path).unlink(missing_ok=True)
        Path(compressed_path).unlink(missing_ok=True)

    version_file = output.parent / "region_osm.version"
    version_file.write_text(f"{DATABASE_VERSION}\n", encoding="ascii")
    print(
        f"Generated {output}: {segment_count:,} segments, "
        f"{output.stat().st_size / (1024 * 1024):.1f} MiB compressed"
    )
    print(f"Generated {version_file}")


if __name__ == "__main__":
    main()
