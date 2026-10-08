# france-map-speed-anticipator
This is an Android app that monitors the vehicle's GPS position and announces detected speed limits.

The local OpenStreetMap database contains roads with explicit speed limits. The app uses the GPS bearing
while the vehicle is moving and selects a nearby road segment whose geometry and direction match that
bearing. A limit must be detected on two consecutive location updates before it is announced.

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
roads with similar directions can still be ambiguous, and each OSM way contributes its explicit
`maxspeed` uniformly across all of its segments.
