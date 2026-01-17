import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class OsmRoadExtractor {

    public static void main(String[] args) throws Exception {
        File inputFile = openFile("input");
        File outputFile = openFile("output");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document inputDoc = builder.parse(inputFile);
        inputDoc.getDocumentElement().normalize();

        Set<String> neededNodeIds = new HashSet<>();

        NodeList wayList = inputDoc.getElementsByTagName("way");

        for (int i = 0; i < wayList.getLength(); i++) {
            Element way = (Element) wayList.item(i);

            if (isRoad(way)) {
                NodeList ndList = way.getElementsByTagName("nd");
                for (int j = 0; j < ndList.getLength(); j++) {
                    Element nd = (Element) ndList.item(j);
                    neededNodeIds.add(nd.getAttribute("ref"));
                }
            }
        }

        Document outputDoc = builder.newDocument();
        Element osmRoot = outputDoc.createElement("osm");
        osmRoot.setAttribute("version", "0.6");
        osmRoot.setAttribute("generator", "OsmRoadExtractor");
        outputDoc.appendChild(osmRoot);

        NodeList nodeList = inputDoc.getElementsByTagName("node");
        for (int i = 0; i < nodeList.getLength(); i++) {
            Element node = (Element) nodeList.item(i);
            if (neededNodeIds.contains(node.getAttribute("id"))) {
                osmRoot.appendChild(outputDoc.importNode(node, true));
            }
        }

        for (int i = 0; i < wayList.getLength(); i++) {
            Element way = (Element) wayList.item(i);
            if (isRoad(way)) {
                osmRoot.appendChild(outputDoc.importNode(way, true));
            }
        }

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(
                new DOMSource(outputDoc),
                new StreamResult(outputFile)
        );

        System.out.println("Done. Roads-only OSM created.");
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

    private static boolean isRoad(Element way) {
        NodeList tags = way.getElementsByTagName("tag");
        for (int i = 0; i < tags.getLength(); i++) {
            Element tag = (Element) tags.item(i);
            if ("highway".equals(tag.getAttribute("k"))) {
                return true;
            }
        }
        return false;
    }
}
