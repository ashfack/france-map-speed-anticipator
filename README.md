# france-map-speed-anticipator
This is an Android app that monitors the vehicle's GPS position and announces detected speed limits.

The local OpenStreetMap database contains roads with explicit speed limits. The app uses the GPS bearing
while the vehicle is moving to match the current road segment, then follows connected OSM segments in
their permitted direction to find the next mapped speed-limit change. A confident match is announced
on the first location update; a distant, poorly aligned, or competing-road match must be confirmed
by a second consistent update. Announcements for the same speed transition are buffered for 30
seconds without suppressing a different transition. Changes within 25 m use an immediate warning
instead of a rounded distance announcement.

The bundled database can be regenerated from a Geofabrik PBF extract. Install `pyosmium`, then run:

```sh
python -m pip install osmium
python scripts/generate_osm_database.py ile-de-france-latest.osm.pbf --replace
```

Version 3 stores coordinates once as integer microdegrees and uses a SQLite R*Tree for spatial lookups,
instead of repeating each segment's bounding box in a large composite index. The database is gzip
compressed in the assets to reduce download and repository size, then expanded into the app's private
database directory at startup. An existing version 2 database can be compacted without the original PBF:

```sh
python scripts/compact_osm_database.py /path/to/region_osm-v2.sqlite /tmp/region_osm-v3.sqlite.gz
mv /tmp/region_osm-v3.sqlite.gz app/src/main/assets/region_osm.sqlite.gz
mv /tmp/region_osm.version app/src/main/assets/region_osm.version
```

The generator and compactor produce the version sidecar alongside the compressed database. On startup,
the app replaces an older installed database with the bundled version. The app requires a GPS bearing
and only uses it above 2.5 m/s (about 9 km/h); a measured bearing accuracy worse than 45 degrees is
rejected.

Segment matching is more precise than way bounding boxes, but is not full route matching: nearby parallel
roads with similar directions can still be ambiguous. At ambiguous intersections prediction stops rather
than guessing a branch. The traversal assumes the most directionally continuous path; it cannot know a
future turn choice. Each OSM way contributes its explicit `maxspeed` uniformly across all of its segments.

To simulate a straight GPS drive against the bundled database, provide the starting coordinates, compass
bearing, speed, and simulated distance:

```sh
python scripts/simulate_route.py 49.0326752 2.3522977 --bearing 0 --speed-kmh 50 \
  --distance-meters 1000 --step-meters 25
```

The simulator follows the same connected-segment and speed-change logic as the Android predictor. A
straight-line replay is useful for diagnostics but does not substitute for a GPX replay on the actual
route, especially where roads curve or branch.
