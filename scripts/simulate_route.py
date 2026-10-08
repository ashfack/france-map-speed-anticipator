#!/usr/bin/env python3
"""Replay a straight simulated GPS drive against the bundled OSM speed database."""

import argparse
import gzip
import math
import sqlite3


METERS_PER_DEGREE = 111_320.0
COORDINATE_SCALE = 1_000_000.0
SEGMENT_SEARCH_RADIUS_METERS = 1_550.0
MAX_ROAD_DISTANCE_METERS = 35.0
MAX_PREDICTION_DISTANCE_METERS = 1_500.0
MIN_HEADING_ALIGNMENT = math.cos(math.radians(50.0))
MIN_TURN_ALIGNMENT = -0.5
HEADING_SCORE_WEIGHT_METERS = 20.0
AMBIGUOUS_BRANCH_ALIGNMENT_GAP = 0.08


def predict(connection, latitude, longitude, bearing):
    latitude_delta = SEGMENT_SEARCH_RADIUS_METERS / METERS_PER_DEGREE
    longitude_scale_factor = max(abs(math.cos(math.radians(latitude))), 0.01)
    longitude_delta = latitude_delta / longitude_scale_factor
    rows = connection.execute(
        """
        SELECT s.segment_id, s.maxspeed, s.oneway,
               s.start_lat_e6, s.start_lon_e6, s.end_lat_e6, s.end_lon_e6
        FROM segment_index AS i
        JOIN segments AS s ON s.segment_id = i.segment_id
        WHERE i.min_lat <= ? AND i.max_lat >= ?
          AND i.min_lon <= ? AND i.max_lon >= ?
        """,
        (
            latitude + latitude_delta,
            latitude - latitude_delta,
            longitude + longitude_delta,
            longitude - longitude_delta,
        ),
    ).fetchall()

    edges = []
    for segment_id, speed, oneway, start_lat, start_lon, end_lat, end_lon in rows:
        segment = (segment_id, speed, start_lat, start_lon, end_lat, end_lon)
        if oneway != -1:
            edges.append(segment)
        if oneway != 1:
            edges.append((segment_id, speed, end_lat, end_lon, start_lat, start_lon))
    adjacency = {}
    for edge in edges:
        adjacency.setdefault((edge[2], edge[3]), []).append(edge)

    heading = math.radians(bearing)
    heading_east = math.sin(heading)
    heading_north = math.cos(heading)
    longitude_scale = METERS_PER_DEGREE * longitude_scale_factor
    matches = []
    for edge in edges:
        start_east, start_north = point_meters(
            edge[2], edge[3], latitude, longitude, longitude_scale
        )
        end_east, end_north = point_meters(
            edge[4], edge[5], latitude, longitude, longitude_scale
        )
        dx = end_east - start_east
        dy = end_north - start_north
        length_squared = dx * dx + dy * dy
        if length_squared == 0:
            continue
        length = math.sqrt(length_squared)
        alignment = (dx * heading_east + dy * heading_north) / length
        if alignment < MIN_HEADING_ALIGNMENT:
            continue
        projection = max(
            0.0,
            min(1.0, -(start_east * dx + start_north * dy) / length_squared),
        )
        closest_east = start_east + projection * dx
        closest_north = start_north + projection * dy
        distance_from_road = math.hypot(closest_east, closest_north)
        if distance_from_road > MAX_ROAD_DISTANCE_METERS:
            continue
        matches.append(
            (
                distance_from_road + (1.0 - alignment) * HEADING_SCORE_WEIGHT_METERS,
                edge,
                (1.0 - projection) * length,
            )
        )

    if not matches:
        return None
    _, current, distance_to_end = min(matches, key=lambda match: match[0])
    distance_along_route = distance_to_end
    previous = current
    visited = {(previous[0], (previous[2], previous[3]))}
    if distance_along_route >= MAX_PREDICTION_DISTANCE_METERS:
        return current[1], None, None

    while distance_along_route < MAX_PREDICTION_DISTANCE_METERS:
        successors = [
            edge
            for edge in adjacency.get((previous[4], previous[5]), [])
            if edge[0] != previous[0] and (edge[0], (edge[2], edge[3])) not in visited
        ]
        next_edge = choose_successor(
            previous, successors, latitude, longitude, longitude_scale
        )
        if next_edge is None:
            return current[1], None, None
        if next_edge[1] != current[1]:
            return current[1], next_edge[1], distance_along_route

        start = point_meters(next_edge[2], next_edge[3], latitude, longitude, longitude_scale)
        end = point_meters(next_edge[4], next_edge[5], latitude, longitude, longitude_scale)
        distance_along_route += math.hypot(end[0] - start[0], end[1] - start[1])
        previous = next_edge
        visited.add((previous[0], (previous[2], previous[3])))

    return current[1], None, None


def point_meters(latitude_e6, longitude_e6, origin_latitude, origin_longitude, longitude_scale):
    return (
        (longitude_e6 / COORDINATE_SCALE - origin_longitude) * longitude_scale,
        (latitude_e6 / COORDINATE_SCALE - origin_latitude) * METERS_PER_DEGREE,
    )


def choose_successor(previous, successors, latitude, longitude, longitude_scale):
    if not successors:
        return None
    incoming_start = point_meters(
        previous[2], previous[3], latitude, longitude, longitude_scale
    )
    incoming_end = point_meters(
        previous[4], previous[5], latitude, longitude, longitude_scale
    )
    incoming_east = incoming_end[0] - incoming_start[0]
    incoming_north = incoming_end[1] - incoming_start[1]
    incoming_length = math.hypot(incoming_east, incoming_north)
    if incoming_length == 0:
        return None

    ranked = []
    for candidate in successors:
        start = point_meters(candidate[2], candidate[3], latitude, longitude, longitude_scale)
        end = point_meters(candidate[4], candidate[5], latitude, longitude, longitude_scale)
        east = end[0] - start[0]
        north = end[1] - start[1]
        length = math.hypot(east, north)
        if length == 0:
            continue
        alignment = (incoming_east * east + incoming_north * north) / (
            incoming_length * length
        )
        if alignment >= MIN_TURN_ALIGNMENT:
            ranked.append((candidate, alignment))
    ranked.sort(key=lambda item: item[1], reverse=True)
    if not ranked:
        return None
    if (
        len(ranked) > 1
        and ranked[0][0][1] != ranked[1][0][1]
        and ranked[0][1] - ranked[1][1] < AMBIGUOUS_BRANCH_ALIGNMENT_GAP
    ):
        return None
    return ranked[0][0]


def destination(latitude, longitude, distance, bearing):
    angle = math.radians(bearing)
    return (
        latitude + distance * math.cos(angle) / METERS_PER_DEGREE,
        longitude
        + distance * math.sin(angle)
        / (METERS_PER_DEGREE * math.cos(math.radians(latitude))),
    )


def update_announcement(prediction, state):
    if prediction is None:
        state["pending"] = None
        state["pending_count"] = 0
        return None

    current_speed, next_speed, distance = prediction
    if current_speed == state["target_speed"]:
        state["target_speed"] = None
        state["current_speed"] = current_speed

    if (
        current_speed != state["current_speed"]
        and current_speed != state["target_speed"]
        and state["pending"] == f"next:{current_speed}"
    ):
        state["current_speed"] = current_speed
        state["pending"] = None
        state["pending_count"] = 0
        return f"La limitation passe à {current_speed} kilomètres heure"

    upcoming = None
    if (
        next_speed is not None
        and distance is not None
        and distance >= 0
        and next_speed != current_speed
        and next_speed != state["target_speed"]
    ):
        if distance < 10:
            upcoming = f"Attention, la limitation va passer à {next_speed} kilomètres heure"
        else:
            rounded_distance = math.floor(distance / 10 + 0.5) * 10
            upcoming = (
                f"Dans environ {rounded_distance} mètres, la limitation passera "
                f"à {next_speed} kilomètres heure"
            )

    current_changed = (
        state["current_speed"] is not None
        and current_speed != state["current_speed"]
        and current_speed != state["target_speed"]
    )
    if current_changed:
        key = f"current:{current_speed}"
    elif upcoming is not None:
        key = f"next:{next_speed}"
    elif current_speed != state["current_speed"] and current_speed != state["target_speed"]:
        key = f"current:{current_speed}"
    else:
        key = None

    if key is None:
        state["pending"] = None
        state["pending_count"] = 0
        return None
    if key == state["pending"]:
        state["pending_count"] += 1
    else:
        state["pending"] = key
        state["pending_count"] = 1
    if state["pending_count"] < 2:
        return None

    if key.startswith("next:") and upcoming is not None:
        state["target_speed"] = next_speed
        state["current_speed"] = current_speed
        message = upcoming
    else:
        state["current_speed"] = current_speed
        message = f"Limitation de vitesse à {current_speed} kilomètres heure"
    state["pending"] = None
    state["pending_count"] = 0
    return message


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("latitude", type=float)
    parser.add_argument("longitude", type=float)
    parser.add_argument("--bearing", type=float, required=True, help="Compass bearing in degrees")
    parser.add_argument("--speed-kmh", type=float, default=50.0)
    parser.add_argument("--distance-meters", type=float, default=1000.0)
    parser.add_argument("--step-meters", type=float, default=25.0)
    parser.add_argument(
        "--database",
        default="app/src/main/assets/region_osm.sqlite.gz",
        help="Compressed v3 database",
    )
    args = parser.parse_args()
    if args.speed_kmh < 9.0:
        parser.error("The app ignores GPS bearings below about 9 km/h")
    if args.step_meters <= 0 or args.distance_meters < 0:
        parser.error("Distance must be non-negative and step must be positive")

    try:
        with gzip.open(args.database, "rb") as compressed:
            database = compressed.read()
        connection = sqlite3.connect(":memory:")
        connection.deserialize(database)
        if connection.execute("PRAGMA user_version").fetchone()[0] != 3:
            parser.error("Database must use schema version 3")
    except (OSError, sqlite3.Error) as error:
        parser.error(f"Cannot open database: {error}")

    print(
        f"Simulated straight drive: {args.speed_kmh:g} km/h, bearing "
        f"{args.bearing % 360:g}°, {args.distance_meters:g} m total"
    )
    print("offset_m  current  next  distance_to_change_m")
    announcement_state = {
        "current_speed": None,
        "target_speed": None,
        "pending": None,
        "pending_count": 0,
    }
    found_match = False
    last_result = None
    distance = 0.0
    while distance <= args.distance_meters:
        latitude, longitude = destination(
            args.latitude, args.longitude, distance, args.bearing
        )
        prediction = predict(connection, latitude, longitude, args.bearing)
        if prediction is not None:
            found_match = True
            current, next_speed, distance_to_change = prediction
            result = (current, next_speed, distance_to_change)
            if distance == 0 or result != last_result:
                if next_speed is None:
                    print(f"{distance:8.0f}  {current:>7}  {'-':>4}  {'-':>21}")
                else:
                    print(
                        f"{distance:8.0f}  {current:>7}  {next_speed:>4}  "
                        f"{distance_to_change:21.1f}"
                    )
            spoken_message = update_announcement(prediction, announcement_state)
            if spoken_message:
                print(f"           voice: {spoken_message}")
            last_result = result
        else:
            update_announcement(None, announcement_state)
        distance += args.step_meters

    if not found_match:
        print(
            "No mapped road matched this position and bearing within 35 m; "
            "the app would not announce a speed limit."
        )
    connection.close()


if __name__ == "__main__":
    main()
