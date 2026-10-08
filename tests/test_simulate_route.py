import importlib.util
import sqlite3
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "simulate_route.py"
SPEC = importlib.util.spec_from_file_location("simulate_route", SCRIPT)
simulator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(simulator)


class RouteSimulationTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript(
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
            """
        )

    def tearDown(self):
        self.db.close()

    def add_segment(self, segment_id, speed, start, end, oneway=0, way_id=None):
        start_lat, start_lon = start
        end_lat, end_lon = end
        start_lat_e6 = round(start_lat * 1_000_000)
        start_lon_e6 = round(start_lon * 1_000_000)
        end_lat_e6 = round(end_lat * 1_000_000)
        end_lon_e6 = round(end_lon * 1_000_000)
        self.db.execute(
            "INSERT INTO segments VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                segment_id,
                segment_id if way_id is None else way_id,
                speed,
                oneway,
                start_lat_e6,
                start_lon_e6,
                end_lat_e6,
                end_lon_e6,
            ),
        )
        self.db.execute(
            "INSERT INTO segment_index VALUES (?, ?, ?, ?, ?)",
            (
                segment_id,
                min(start_lon, end_lon),
                max(start_lon, end_lon),
                min(start_lat, end_lat),
                max(start_lat, end_lat),
            ),
        )

    def test_follows_connected_roads_and_returns_change_distance(self):
        self.add_segment(1, 50, (0, 0), (0, 0.001))
        self.add_segment(2, 50, (0, 0.001), (0, 0.002))
        self.add_segment(3, 30, (0, 0.002), (0, 0.003))

        prediction = simulator.predict(self.db, 0, 0.00025, 90)

        self.assertEqual(prediction[:2], (50, 30))
        self.assertAlmostEqual(prediction[2], 194.81, delta=1.0)

    def test_respects_reverse_oneway_segments(self):
        self.add_segment(1, 30, (0, 0), (0, 0.001), oneway=-1)
        self.add_segment(2, 50, (0, 0.001), (0, 0.002), oneway=-1)

        prediction = simulator.predict(self.db, 0, 0.00175, 270)

        self.assertEqual(prediction[:2], (50, 30))

    def test_does_not_cross_a_disconnected_gap(self):
        self.add_segment(1, 50, (0, 0), (0, 0.001))
        self.add_segment(2, 30, (0, 0.0011), (0, 0.002))

        prediction = simulator.predict(self.db, 0, 0.00025, 90)

        self.assertEqual(prediction[:3], (50, None, None))

    def test_requires_confirmation_for_equally_plausible_parallel_ways(self):
        self.add_segment(1, 50, (0, 0), (0, 0.002), way_id=10)
        self.add_segment(2, 50, (0.00001, 0), (0.00001, 0.002), way_id=20)

        prediction = simulator.predict(self.db, 0.000005, 0.00025, 90)

        self.assertTrue(prediction[3])

    def test_stops_prediction_at_an_ambiguous_intersection(self):
        self.add_segment(1, 50, (0, 0), (0, 0.001))
        self.add_segment(2, 50, (0, 0.001), (0, 0.002))
        self.add_segment(3, 30, (0, 0.001), (0.00005, 0.002))

        prediction = simulator.predict(self.db, 0, 0.00025, 90)

        self.assertEqual(prediction[:3], (50, None, None))

    def test_confident_announcement_is_immediate_and_buffered(self):
        state = {
            "current_speed": None,
            "target_speed": None,
            "pending": None,
            "pending_count": 0,
            "announced": {},
            "now_ms": 0,
        }

        first = simulator.update_announcement((50, 30, 32.0, False), state)
        state["now_ms"] = 1_000
        second = simulator.update_announcement((50, 30, 7.0, False), state)

        self.assertEqual(first, "Dans environ 30 mètres, la limitation passera à 30 kilomètres heure")
        self.assertIsNone(second)

    def test_uncertain_announcement_requires_two_consistent_updates(self):
        state = {
            "current_speed": None,
            "target_speed": None,
            "pending": None,
            "pending_count": 0,
            "announced": {},
            "now_ms": 0,
        }

        first = simulator.update_announcement((50, 30, 120.0, True), state)
        state["now_ms"] = 1_000
        second = simulator.update_announcement((50, 30, 120.0, True), state)

        self.assertIsNone(first)
        self.assertEqual(second, "Dans environ 120 mètres, la limitation passera à 30 kilomètres heure")

    def test_nearby_transition_uses_immediate_warning(self):
        state = {
            "current_speed": None,
            "target_speed": None,
            "pending": None,
            "pending_count": 0,
            "announced": {},
            "now_ms": 0,
        }

        announcement = simulator.update_announcement((50, 30, 7.0, False), state)

        self.assertEqual(
            announcement,
            "Attention, la limitation va passer à 30 kilomètres heure",
        )


if __name__ == "__main__":
    unittest.main()
