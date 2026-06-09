# Campus Running Route Generator

A custom, data-driven geospatial routing engine built from scratch in Java. The application parses raw OpenStreetMap (OSM) XML maps, dynamically builds a contiguous topological network graph, and leverages an advanced Dijkstra pathfinding implementation to generate closed-circuit running loops based on a target distance input.

## Features Built
- **Custom SAX Stream Parser:** Efficiently streams and structures massive map files into runtime memory.
- **Topological Graph Engine:** Constructs dynamic adjacency lists mapping physical latitude and longitude coordinates to real-world street nodes.
- **Damped Proportional Loop Calibration:** Solves the Manhattan-vs-Euclidean distance variance by iteratively self-correcting its waypoint radius to match the user's requested mileage.
- **Island Filtering Pass:** Uses reachability checks to drop isolated walkways or building clusters, ensuring full loop integrity.
- **GPX Export Layer:** Automatically outputs industry-standard `.gpx` files ready to render directly on mapping software like Strava, Garmin, or GpxEditor.
