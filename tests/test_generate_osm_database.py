import importlib.util
import sqlite3
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).parents[1] / "scripts"


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


generator = load_script("generate_osm_database")
compactor = load_script("compact_osm_database")


class ParseMaxspeedTests(unittest.TestCase):
    def test_parses_kilometers_per_hour(self):
        self.assertEqual(generator.parse_maxspeed("80 km/h"), 80)

    def test_converts_miles_per_hour(self):
        self.assertEqual(generator.parse_maxspeed("50 mph"), 80)

    def test_uses_first_semicolon_separated_value(self):
        self.assertEqual(generator.parse_maxspeed("30;50"), 30)

    def test_skips_symbolic_and_invalid_values(self):
        self.assertIsNone(generator.parse_maxspeed("FR:urban"))
        self.assertIsNone(generator.parse_maxspeed("none"))
        self.assertIsNone(generator.parse_maxspeed("0"))


class ParseOnewayTests(unittest.TestCase):
    def test_reads_explicit_direction(self):
        self.assertEqual(generator.parse_oneway({"oneway": "yes"}), 1)
        self.assertEqual(generator.parse_oneway({"oneway": "-1"}), -1)
        self.assertEqual(generator.parse_oneway({"oneway": "no"}), 0)

    def test_applies_implicit_oneway_for_roundabouts_and_motorways(self):
        self.assertEqual(
            generator.parse_oneway({"junction": "roundabout", "highway": "residential"}),
            1,
        )
        self.assertEqual(generator.parse_oneway({"highway": "motorway"}), 1)


class DatabaseGenerationTests(unittest.TestCase):
    def test_generates_directional_segments_between_consecutive_nodes(self):
        class Location:
            def __init__(self, lat, lon, valid=True):
                self.lat = lat
                self.lon = lon
                self._valid = valid

            def valid(self):
                return self._valid

        class Node:
            def __init__(self, lat, lon, valid=True):
                self.location = Location(lat, lon, valid)

        class Way:
            id = 42
            tags = {
                "highway": "residential",
                "maxspeed": "50",
                "oneway": "-1",
                "name": "Rue Test",
            }
            nodes = [
                Node(48.0, 2.0),
                Node(48.001, 2.001),
                Node(0.0, 0.0, valid=False),
                Node(48.002, 2.002),
                Node(48.003, 2.003),
            ]

        connection = sqlite3.connect(":memory:")
        cursor = generator.create_schema(connection)
        handler = generator.SpeedLimitHandler(cursor)
        handler.way(Way())
        handler.flush()

        rows = connection.execute(
            """
            SELECT start_lat_e6, end_lat_e6, oneway
            FROM segments ORDER BY segment_id
            """
        ).fetchall()
        self.assertEqual(
            rows,
            [(48_000_000, 48_001_000, -1), (48_002_000, 48_003_000, -1)],
        )
        self.assertEqual(
            connection.execute("SELECT count(*) FROM segment_index").fetchone()[0],
            2,
        )
        self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 3)
        connection.close()


class DatabaseCompactionTests(unittest.TestCase):
    def test_compacts_old_segments_and_builds_spatial_index(self):
        source = sqlite3.connect(":memory:")
        source.executescript(
            """
            CREATE TABLE segments (
                segment_id INTEGER PRIMARY KEY, way_id INTEGER, maxspeed INTEGER,
                oneway INTEGER, start_lat REAL, start_lon REAL,
                end_lat REAL, end_lon REAL
            );
            PRAGMA user_version = 2;
            """
        )
        source.execute(
            "INSERT INTO segments VALUES (1, 42, 50, 1, 48.0, 2.0, 48.001, 2.001)"
        )
        destination = sqlite3.connect(":memory:")
        compactor.create_schema(destination)
        compactor.copy_segments(source, destination)

        segment = destination.execute(
            """
            SELECT start_lat_e6, start_lon_e6, end_lat_e6, end_lon_e6
            FROM segments WHERE segment_id = 1
            """
        ).fetchone()
        indexed_segment = destination.execute(
            "SELECT min_lat, min_lon, max_lat, max_lon FROM segment_index WHERE segment_id = 1"
        ).fetchone()
        self.assertEqual(
            segment,
            (48_000_000, 2_000_000, 48_001_000, 2_001_000),
        )
        self.assertLessEqual(indexed_segment[0], 48.0)
        self.assertLessEqual(indexed_segment[1], 2.0)
        self.assertGreaterEqual(indexed_segment[2], 48.001)
        self.assertGreaterEqual(indexed_segment[3], 2.001)
        self.assertEqual(destination.execute("PRAGMA user_version").fetchone()[0], 3)
        source.close()
        destination.close()


if __name__ == "__main__":
    unittest.main()
