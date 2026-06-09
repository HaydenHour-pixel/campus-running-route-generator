import java.io.File;
import java.io.PrintWriter;
import java.util.*;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

// ==========================================
// 1. THE CORE DATA STRUCTURES
// ==========================================

class Node {
    long id;
    double lat;
    double lon;

    public Node(long id, double lat, double lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node)) return false;
        Node node = (Node) o;
        return id == node.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Node " + id + " (" + lat + ", " + lon + ")";
    }
}

class Edge {
    Node source;
    Node destination;
    double distance; 
    String roadType; 

    public Edge(Node source, Node destination, double distance, String roadType) {
        this.source = source;
        this.destination = destination;
        this.distance = distance;
        this.roadType = roadType;
    }

    public double getWeight(boolean avoidMainRoads) {
        if (avoidMainRoads && (roadType.equals("motorway") || roadType.equals("primary") || roadType.equals("secondary"))) {
            return this.distance * 50.0; // 50x penalty to stay on pedestrian paths
        }
        return this.distance;
    }
}

class Graph {
    private final Map<Long, List<Edge>> adjacencyList = new HashMap<>();
    private final Map<Long, Node> nodes = new HashMap<>();

    public void addNode(Node node) {
        nodes.put(node.id, node);
        adjacencyList.putIfAbsent(node.id, new ArrayList<>());
    }

    public void addEdge(long sourceId, long destId, double distance, String roadType) {
        Node src = nodes.get(sourceId);
        Node dest = nodes.get(destId);
        if (src != null && dest != null) {
            Edge edge = new Edge(src, dest, distance, roadType);
            adjacencyList.get(sourceId).add(edge);
        }
    }

    public List<Edge> getNeighbors(long nodeId) {
        return adjacencyList.getOrDefault(nodeId, new ArrayList<>());
    }

    public Node getNode(long id) {
        return nodes.get(id);
    }

    public Collection<Node> getNodesCollection() {
        return this.nodes.values();
    }
}

// ==========================================
// 2. THE CUSTOM OSM SAX PARSER HANDLER
// ==========================================

class OSMHandler extends DefaultHandler {
    private final Graph graph;
    private boolean isInsideWay = false;
    private final List<Long> currentWayNodeIds = new ArrayList<>();
    private String currentRoadType = "unknown";
    
    public OSMHandler(Graph graph) {
        this.graph = graph;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        if (qName.equalsIgnoreCase("node")) {
            long id = Long.parseLong(attributes.getValue("id"));
            double lat = Double.parseDouble(attributes.getValue("lat"));
            double lon = Double.parseDouble(attributes.getValue("lon"));
            graph.addNode(new Node(id, lat, lon));
        } 
        else if (qName.equalsIgnoreCase("way")) {
            isInsideWay = true;
            currentWayNodeIds.clear();
            currentRoadType = "unknown";
        } 
        else if (qName.equalsIgnoreCase("nd") && isInsideWay) {
            long nodeRef = Long.parseLong(attributes.getValue("ref"));
            currentWayNodeIds.add(nodeRef);
        } 
        else if (qName.equalsIgnoreCase("tag") && isInsideWay) {
            String key = attributes.getValue("k");
            String value = attributes.getValue("v");
            if (key != null && key.equalsIgnoreCase("highway")) {
                currentRoadType = value;
            }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        if (qName.equalsIgnoreCase("way")) {
            isInsideWay = false;

            for (int i = 0; i < currentWayNodeIds.size() - 1; i++) {
                long srcId = currentWayNodeIds.get(i);
                long destId = currentWayNodeIds.get(i + 1);
                
                if (graph.getNode(srcId) == null) {
                    graph.addNode(new Node(srcId, 42.3868, -72.5301)); 
                }
                if (graph.getNode(destId) == null) {
                    graph.addNode(new Node(destId, 42.3868, -72.5301));
                }

                Node srcNode = graph.getNode(srcId);
                Node destNode = graph.getNode(destId);
                
                double dist = calculateHaversineDistance(srcNode, destNode);
                
                if (dist < 1.0) {
                    dist = 15.0; 
                }

                graph.addEdge(srcId, destId, dist, currentRoadType);
                graph.addEdge(destId, srcId, dist, currentRoadType);
            }
        }
    }

    private double calculateHaversineDistance(Node n1, Node n2) {
        double R = 6371000; 
        double dLat = Math.toRadians(n2.lat - n1.lat);
        double dLon = Math.toRadians(n2.lon - n1.lon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(n1.lat)) * Math.cos(Math.toRadians(n2.lat)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}

// ==========================================
// 3. THE DIJKSTRA & LOOP ROUTING ENGINE
// ==========================================

class DijkstraRouter {
    private final Graph graph;

    public DijkstraRouter(Graph graph) {
        this.graph = graph;
    }

    private static class PQNode implements Comparable<PQNode> {
        Node node;
        double priorityDistance;

        public PQNode(Node node, double priorityDistance) {
            this.node = node;
            this.priorityDistance = priorityDistance;
        }

        @Override
        public int compareTo(PQNode other) {
            return Double.compare(this.priorityDistance, other.priorityDistance);
        }
    }

    public List<Node> findShortestPath(Node start, Node end, boolean avoidMainRoads) {
        Map<Long, Double> distances = new HashMap<>();
        Map<Long, Node> parentMap = new HashMap<>();
        PriorityQueue<PQNode> pq = new PriorityQueue<>();

        pq.add(new PQNode(start, 0.0));
        distances.put(start.id, 0.0);

        while (!pq.isEmpty()) {
            PQNode currentPQNode = pq.poll();
            Node u = currentPQNode.node;

            if (u.id == end.id) {
                break;
            }

            if (currentPQNode.priorityDistance > distances.getOrDefault(u.id, Double.MAX_VALUE)) {
                continue;
            }

            for (Edge edge : graph.getNeighbors(u.id)) {
                Node v = edge.destination;
                double weight = edge.getWeight(avoidMainRoads);
                double newDist = distances.get(u.id) + weight;

                if (newDist < distances.getOrDefault(v.id, Double.MAX_VALUE)) {
                    distances.put(v.id, newDist);
                    parentMap.put(v.id, u);
                    pq.add(new PQNode(v, newDist));
                }
            }
        }

        List<Node> path = new ArrayList<>();
        if (!parentMap.containsKey(end.id) && start.id != end.id) {
            return path;
        }

        Node current = end;
        while (current != null) {
            path.add(0, current);
            current = parentMap.get(current.id);
        }
        return path;
    }

    public List<Node> generateLoop(Node start, double targetDistanceKm, boolean avoidMainRoads) {
        List<Node> finalLoop = new ArrayList<>();
        
        // Track the absolute best-fit run across all attempts
        List<Node> bestFitLoop = new ArrayList<>();
        double bestFitError = Double.MAX_VALUE;
        double bestFitDistanceKm = 0.0;

        double calibrationFactor = 0.45; 
        int maxAttempts = 5;
        double toleranceKm = 0.25; 
        
        System.out.printf("DEBUG -> Target Distance Requested: %.2f km%n", targetDistanceKm);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<Node> candidateLoop = new ArrayList<>();
            double legDistanceKm = (targetDistanceKm / 3.0) * calibrationFactor;
            
            double latOffset = legDistanceKm / 111.0;
            double lonOffset = legDistanceKm / 82.0;
            
            double wp1Lat = start.lat + latOffset;
            double wp1Lon = start.lon + lonOffset;
            
            double wp2Lat = start.lat - latOffset;
            double wp2Lon = start.lon - (lonOffset * 0.4); 
            
            Node wp1Node = findRobustIntersection(wp1Lat, wp1Lon, start, avoidMainRoads);
            Node wp2Node = findRobustIntersection(wp2Lat, wp2Lon, start, avoidMainRoads);
            
            if (wp1Node == null || wp2Node == null) continue;
            
            List<Node> leg1 = findShortestPath(start, wp1Node, avoidMainRoads);
            if (leg1.isEmpty() && avoidMainRoads) leg1 = findShortestPath(start, wp1Node, false);

            List<Node> leg2 = findShortestPath(wp1Node, wp2Node, avoidMainRoads);
            if (leg2.isEmpty() && avoidMainRoads) leg2 = findShortestPath(wp1Node, wp2Node, false);

            List<Node> leg3 = findShortestPath(wp2Node, start, avoidMainRoads);
            if (leg3.isEmpty() && avoidMainRoads) leg3 = findShortestPath(wp2Node, start, false);
            
            if (!leg1.isEmpty()) candidateLoop.addAll(leg1);
            if (!leg2.isEmpty()) candidateLoop.addAll(leg2.subList(1, leg2.size()));
            if (!leg3.isEmpty()) candidateLoop.addAll(leg3.subList(1, leg3.size()));
            
            // Calculate the physical length of this attempted loop
            double currentDistanceMeters = 0.0;
            double R = 6371000;
            for (int i = 0; i < candidateLoop.size() - 1; i++) {
                Node n1 = candidateLoop.get(i); Node n2 = candidateLoop.get(i + 1);
                double dLat = Math.toRadians(n2.lat - n1.lat);
                double dLon = Math.toRadians(n2.lon - n1.lon);
                double a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(Math.toRadians(n1.lat))*Math.cos(Math.toRadians(n2.lat))*Math.sin(dLon/2)*Math.sin(dLon/2);
                currentDistanceMeters += (R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))));
            }
            
            double currentDistanceKm = currentDistanceMeters / 1000.0;
            System.out.printf("   [Attempt %d] Calibration Factor: %.3f -> Generated Route Length: %.2f km%n", 
                            attempt, calibrationFactor, currentDistanceKm);
            
            // Track the absolute closest loop to the target
            double currentError = Math.abs(currentDistanceKm - targetDistanceKm);
            if (currentError < bestFitError) {
                bestFitError = currentError;
                bestFitLoop = new ArrayList<>(candidateLoop);
                bestFitDistanceKm = currentDistanceKm;
            }

            // Perfect match check
            if (currentError <= toleranceKm) {
                finalLoop = candidateLoop;
                System.out.printf("DEBUG -> Target locked on attempt %d! final calibration factor used: %.3f%n", attempt, calibrationFactor);
                return finalLoop;
            }
            
            // Damped adjustment
            double rawRatio = targetDistanceKm / currentDistanceKm;
            double dampedRatio = 1.0 + ((rawRatio - 1.0) * 0.5); 
            calibrationFactor = calibrationFactor * dampedRatio;
            
            if (calibrationFactor < 0.1) calibrationFactor = 0.1;
            if (calibrationFactor > 2.0) calibrationFactor = 2.0;
        }
        
        // --- BEST-FIT FALLBACK ---
        // If the graph geometry forces an oscillation cliff, return the single closest option generated
        System.out.printf("DEBUG -> Physical map data threshold cliff hit. Returning closest best-fit option (%.2f km).%n", bestFitDistanceKm);
        return bestFitLoop;
    }

    // Sorts candidate nodes by proximity and guarantees they can reach the core network 
    private Node findRobustIntersection(double targetLat, double targetLon, Node startNode, boolean avoidMainRoads) {
        List<Node> sortedNodes = new ArrayList<>(graph.getNodesCollection());
        sortedNodes.sort((n1, n2) -> {
            double dLat1 = n1.lat - targetLat;
            double dLon1 = n1.lon - targetLon;
            double dist1 = dLat1 * dLat1 + dLon1 * dLon1;

            double dLat2 = n2.lat - targetLat;
            double dLon2 = n2.lon - targetLon;
            double dist2 = dLat2 * dLat2 + dLon2 * dLon2;

            return Double.compare(dist1, dist2);
        });

        for (Node candidate : sortedNodes) {
            if (candidate.lat == 42.3868 && candidate.lon == -72.5301) continue;
            
            // Junction verification
            if (graph.getNeighbors(candidate.id).size() < 3) {
                continue; 
            }

            // Connectivity validation path
            List<Node> testPath = findShortestPath(startNode, candidate, false);
            if (!testPath.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }
}

// ==========================================
// 4. MAIN RUNNER & LOADER WITH EXPORTERS
// ==========================================

public class RoutingEngine {

    public static Graph loadGraphFromOSM(String filePath) {
        Graph graph = new Graph();
        try {
            File inputFile = new File(filePath);
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            OSMHandler handler = new OSMHandler(graph);
            
            System.out.println("Parsing OSM map file: " + filePath);
            saxParser.parse(inputFile, handler);
            System.out.println("Parsing complete! Total nodes in memory: " + graph.getNodesCollection().size());
            
        } catch (Exception e) {
            System.err.println("Error parsing the OSM file. Check if the file path is correct.");
            e.printStackTrace();
        }
        return graph;
    }

    private static double calculateTotalLoopDistance(List<Node> loopPath) {
        double totalDistanceMeters = 0.0;
        double R = 6371000; 

        for (int i = 0; i < loopPath.size() - 1; i++) {
            Node n1 = loopPath.get(i);
            Node n2 = loopPath.get(i + 1);

            double dLat = Math.toRadians(n2.lat - n1.lat);
            double dLon = Math.toRadians(n2.lon - n1.lon);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                       Math.cos(Math.toRadians(n1.lat)) * Math.cos(Math.toRadians(n2.lat)) *
                       Math.sin(dLon / 2) * Math.sin(dLon / 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            totalDistanceMeters += (R * c);
        }
        return totalDistanceMeters;
    }

    private static void exportToGPX(List<Node> loopPath, String outputFilename) {
        try (PrintWriter writer = new PrintWriter(new File(outputFilename))) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writer.println("<gpx version=\"1.1\" creator=\"JavaRunningApp\" xmlns=\"http://www.topografix.com/GPX/1/1\">");
            writer.println("  <trk>");
            writer.println("    <name>UMass Amherst Generated Run</name>");
            writer.println("    <trkseg>");

            for (Node node : loopPath) {
                writer.println("      <trkpt lat=\"" + node.lat + "\" lon=\"" + node.lon + "\"></trkpt>");
            }

            writer.println("    </trkseg>");
            writer.println("  </trk>");
            writer.println("</gpx>");
            System.out.println("\n[+] Map track successfully exported to: " + outputFilename);
        } catch (Exception e) {
            System.err.println("Failed to write GPX export file.");
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        String mapFile = "map.osm"; 
        Graph realMap = loadGraphFromOSM(mapFile);
        
        if (realMap.getNodesCollection().isEmpty()) {
            System.out.println("\n[!] The graph is empty. Ensure 'map.osm' is saved in your project folder!");
            return;
        }

        Node bestStartNode = null;
        int maxNeighbors = -1;

        for (Node node : realMap.getNodesCollection()) {
            if (node.lat == 42.3868 && node.lon == -72.5301) continue;
            int neighborCount = realMap.getNeighbors(node.id).size();
            if (neighborCount > maxNeighbors) {
                maxNeighbors = neighborCount;
                bestStartNode = node;
            }
        }

        if (bestStartNode == null || maxNeighbors == 0) {
            System.out.println("[!] Error: Graph network connectivity error.");
            return;
        }
        
        System.out.println("\nStarting loop generation from a major intersection!");
        System.out.println("Selected Node: " + bestStartNode + " with " + maxNeighbors + " connections.");
        
        DijkstraRouter router = new DijkstraRouter(realMap);
        
        // Target settings
        double targetDistanceKm = 5.0;
        boolean avoidMainRoads = true;
        
        List<Node> realLoop = router.generateLoop(bestStartNode, targetDistanceKm, avoidMainRoads);
        
        System.out.println("\n--- Generated Real-World Running Loop ---");
        if (realLoop.isEmpty()) {
            System.out.println("Could not find a valid looping path.");
        } else {
            double finalDistanceMeters = calculateTotalLoopDistance(realLoop);
            System.out.printf("Success! Loop contains %d path nodes.%n", realLoop.size());
            System.out.printf("Calculated Route Distance: %.2f meters (%.2f km)%n", finalDistanceMeters, finalDistanceMeters / 1000.0);
            
            exportToGPX(realLoop, "umass_route.gpx");
        }
    }
}