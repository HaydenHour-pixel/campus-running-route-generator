import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import java.util.ArrayList;
import java.util.List;

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
            
            // Add or update the node with its real real-world coordinates
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
            
            // --- DIAGNOSTIC PRINT ---
            // Let's see what types of ways are actually passing through your file!
            // System.out.println("Processing way type: " + currentRoadType + " with " + currentWayNodeIds.size() + " nodes.");

            for (int i = 0; i < currentWayNodeIds.size() - 1; i++) {
                long srcId = currentWayNodeIds.get(i);
                long destId = currentWayNodeIds.get(i + 1);
                
                Node srcNode = graph.getNode(srcId);
                Node destNode = graph.getNode(destId);
                
                if (srcNode != null && destNode != null) {
                    double dist = calculateHaversineDistance(srcNode, destNode);
                    graph.addEdge(srcId, destId, dist, currentRoadType);
                    graph.addEdge(destId, srcId, dist, currentRoadType);
                }
            }
        }
    }
    private double calculateHaversineDistance(Node n1, Node n2) {
        double R = 6371000; // Earth's radius in meters
        double dLat = Math.toRadians(n2.lat - n1.lat);
        double dLon = Math.toRadians(n2.lon - n1.lon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(n1.lat)) * Math.cos(Math.toRadians(n2.lat)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}