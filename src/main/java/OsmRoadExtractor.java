import javax.xml.stream.*;
import java.io.*;
import java.util.*;

enum ElementType {
    NODE,
    WAY,
    RELATION
}

public class OsmRoadExtractor {

    private static class Member {
        Long id;
        ElementType type;

        public Member(Long id, ElementType t) {
            this.id = id;
            this.type = t;
        }
    }

    private static class Counter {
        long total, nodes, ways, relations;

        public Counter(long t, long n, long w, long r) {
            this.total = t;
            this.nodes = n;
            this.ways = w;
            this.relations = r;
        }
    }

    private static void printMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();

        long totalMemory = runtime.totalMemory(); // Current size of the JVM heap
        long freeMemory = runtime.freeMemory();   // Unused memory within the current heap

        long usedMemory = totalMemory - freeMemory;
        System.out.printf("Used Memory: %.2f MB%n", usedMemory / (1024.0 * 1024.0));
    }

    public static void main(String[] args) throws Exception {
        File inputFile = openFile("input");
        File outputFile = openFile("output");

        boolean extended = askAboutRelations();

        long startTime = System.nanoTime();

        Set<Long> neededNodeIds = new HashSet<>();
        Set<Long> neededWayIds = new HashSet<>();
        Set<Long> neededRelationIds = new HashSet<>();

        System.out.println("Collecting data...");
        System.out.println();

        Counter counter = collectData(inputFile, neededNodeIds, neededWayIds, neededRelationIds, extended);

        System.out.println("Total XML size (in elements): " + counter.total + ". Nodes: " + counter.nodes + ". Ways: " + counter.ways + ". Relations: " + counter.relations + ".");
        System.out.println("Found " + neededNodeIds.size() + " nodes belonging to roads.");
        System.out.println("Found " + neededWayIds.size() + " ways that are roads.");
        if(extended) System.out.println("Found " + neededRelationIds.size() + " relations having at least one member in the filtered map.");
        System.out.println();

        System.out.println("Writing filtered OSM file...");
        writeFilteredOsm(inputFile, outputFile, neededNodeIds, neededWayIds, neededRelationIds, extended);
        System.out.println("Done. Roads-only OSM created.");
        System.out.println();

        printMemoryUsage();

        long durationNano = System.nanoTime() - startTime;
        double durationSeconds = durationNano / 1_000_000_000.0;

        System.out.printf("Total time running: %.2f seconds%n", durationSeconds);
    }

    // Read-only pass to collect node IDs
    static Counter collectData(
            File inputFile,
            Set<Long> neededNodeIds,
            Set<Long> neededWayIds,
            Set<Long> neededRelationIds,
            boolean extended
    ) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        long elementsCount = 0, nodesCount = 0, waysCount = 0, relationsCount = 0;

        try (FileInputStream fis = new FileInputStream(inputFile)) {
            XMLStreamReader reader = factory.createXMLStreamReader(fis);

            Set<Long> tempWayNodes = new HashSet<>();
            boolean isRoad = false;
            boolean inWay = false;
            long wayId = -1;

            Map<Long, Set<Member>> relations = new HashMap<>();
            long relationId = -1;

            while (reader.hasNext()) {
                int event = reader.next();

                switch(event) {
                    case XMLStreamConstants.START_ELEMENT:
                        String elementName = reader.getLocalName();

                        if ("way".equals(elementName) || "node".equals(elementName) || "relation".equals(elementName)) elementsCount++;
                        if ("node".equals(elementName)) nodesCount++;
                        if ("way".equals(elementName)) waysCount++;
                        if ("relation".equals(elementName)) relationsCount++;

                        if ("way".equals(elementName)) {
                            wayId = Long.parseLong(reader.getAttributeValue(null, "id"));
                            inWay = true;
                            tempWayNodes.clear();
                            isRoad = false;
                        }
                        else if (inWay && "nd".equals(elementName)) {
                            tempWayNodes.add(Long.parseLong(reader.getAttributeValue(null, "ref")));
                        }
                        else if (inWay && "tag".equals(elementName)) {
                            String k = reader.getAttributeValue(null, "k");
                            if ("highway".equals(k)) {
                                isRoad = true;
                                neededWayIds.add(wayId);
                            }
                        }

                        // Relations
                        if (extended) {
                            if ("relation".equals(elementName)) {
                                relationId = Long.parseLong(reader.getAttributeValue(null, "id"));
                            }
                            else if ("member".equals(elementName)) {
                                long memberId = Long.parseLong(reader.getAttributeValue(null, "ref"));

                                ElementType type = ElementType.NODE;
                                type = switch (reader.getAttributeValue(null, "type")) {
                                    case "way" -> ElementType.WAY;
                                    case "node" -> ElementType.NODE;
                                    case "relation" -> ElementType.RELATION;
                                    default -> type;
                                };

                                relations.putIfAbsent(relationId, new HashSet<>());
                                relations.get(relationId).add(new Member(memberId, type));
                            }
                        }

                        break;
                    case XMLStreamConstants.END_ELEMENT:
                        if ("way".equals(reader.getLocalName())) {
                            inWay = false;
                            if (isRoad) {
                                neededNodeIds.addAll(tempWayNodes);
                            }
                        }
                        break;
                }
            }
            reader.close();

            if(extended) computeRelations(neededNodeIds, neededWayIds, neededRelationIds, relations);
        }
        return new Counter(elementsCount, nodesCount, waysCount, relationsCount);
    }

    // Read and Write pass
    private static void writeFilteredOsm(
            File inputFile,
            File outputFile,
            Set<Long> neededNodeIds,
            Set<Long> neededWayIds,
            Set<Long> neededRelationIds,
            boolean extended
    ) throws Exception {
        XMLInputFactory inFactory = XMLInputFactory.newInstance();
        XMLOutputFactory outFactory = XMLOutputFactory.newInstance();

        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(outputFile)) {

            XMLStreamReader reader = inFactory.createXMLStreamReader(fis);
            XMLStreamWriter writer = outFactory.createXMLStreamWriter(fos, "UTF-8");

            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeCharacters("\n");

            boolean skippingElement = false; // Write osm root

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        String localName = reader.getLocalName();

                        // Decide if we should keep this node
                        if ("node".equals(localName)) {
                            Long id = Long.parseLong(reader.getAttributeValue(null, "id"));
                            skippingElement = !neededNodeIds.contains(id);
                        }
                        else if ("way".equals(localName)) {
                            Long id = Long.parseLong(reader.getAttributeValue(null, "id"));
                            skippingElement = !neededWayIds.contains(id);
                        }
                        else if ("relation".equals(localName)) {
                            if(!extended) {
                                skippingElement = true;
                                continue;
                            }

                            Long id = Long.parseLong(reader.getAttributeValue(null, "id"));
                            skippingElement = !neededRelationIds.contains(id);
                        }

                        if (!skippingElement) {
                            writer.writeStartElement(localName);
                            // Copy all attributes
                            for (int i = 0; i < reader.getAttributeCount(); i++) {
                                writer.writeAttribute(
                                        reader.getAttributeLocalName(i),
                                        reader.getAttributeValue(i)
                                );
                            }
                        }
                        break;

                    case XMLStreamConstants.END_ELEMENT:
                        if (!skippingElement) {
                            writer.writeEndElement();
                            writer.writeCharacters("\n");
                        }

                        String elementName = reader.getLocalName();
                        if ("way".equals(elementName) || "node".equals(elementName) || "relation".equals(elementName)) {
                            skippingElement = true;
                        }
                        break;
                }
            }

            // Close the root
            writer.writeEndElement(); // Closes </osm>
            writer.writeCharacters("\n");

            writer.writeEndDocument();
            writer.flush();
            writer.close();
            reader.close();
        }
    }

    // Keep relations that have at least one member present in the map (other relations included, which requires recursion to process)
    private static void computeRelations(Set<Long> nodes, Set<Long> ways, Set<Long> relationIds, Map<Long, Set<Member>> relations) {
        boolean isChanged = true;

        while (isChanged && !relations.isEmpty()) {
            isChanged = false;
            Set<Long> tmpKeepIds = new HashSet<>();

            for(Long rId : relations.keySet()) {
                boolean shouldKeep = false;
                Set<Member> checked = new HashSet<>();

                for(Member member : relations.get(rId)) {
                    Long id = member.id;
                    ElementType type = member.type;

                    switch (type) {
                        case NODE:
                            if(nodes.contains(id)) shouldKeep = true;
                            else checked.add(member);
                            break;
                        case WAY:
                            if(ways.contains(id)) shouldKeep = true;
                            else checked.add(member);
                            break;
                        case RELATION:
                            if(relationIds.contains(id)) shouldKeep = true;
                            break;
                    }

                    if(shouldKeep) break;
                }

                if(shouldKeep) {
                    tmpKeepIds.add(rId);
                    isChanged = true;
                } else {
                    relations.get(rId).removeAll(checked);
                }
            }

            if(isChanged) {
                relationIds.addAll(tmpKeepIds);
                relations.keySet().removeAll(tmpKeepIds);
            }
        }
    }

    private static boolean askAboutRelations() {
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.println("Keep relevant relations? [Y / N]");
            String a = in.next().toLowerCase();

            if(a.equals("y") || a.equals("yes")) {
                return true;
            }
            if(a.equals("n") || a.equals("no") || a.isEmpty()) {
                return false;
            }
        }
    }

    private static File openFile(String dir) {
        Scanner in = new Scanner(System.in);

        System.out.print(dir.substring(0, 1).toUpperCase() + dir.substring(1) + " map name: ");
        String fileName = in.next();

        if (!fileName.toLowerCase().endsWith(".osm")) {
            fileName += ".osm";
        }

        File file = new File(dir + "/" + fileName);
        file.getParentFile().mkdirs();

        return file;
    }
}
