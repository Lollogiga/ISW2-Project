package it.project.utils;

import it.project.entities.Smell;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

public class PmdParser {

    public Map<String, List<Smell>> parseReport(File reportFile, File repoRoot) {
        Map<String, List<Smell>> fileToSmells = new HashMap<>();

        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(reportFile);
            doc.getDocumentElement().normalize();

            NodeList fileNodes = doc.getElementsByTagName("file");

            for (int i = 0; i < fileNodes.getLength(); i++) {
                Element fileElement = (Element) fileNodes.item(i);
                String absPath = fileElement.getAttribute("name");
                File absFile = new File(absPath);
                String relPath = repoRoot.toURI().relativize(absFile.toURI()).getPath();

                NodeList violations = fileElement.getElementsByTagName("violation");

                for (int j = 0; j < violations.getLength(); j++) {
                    Element v = (Element) violations.item(j);
                    int begin = Integer.parseInt(v.getAttribute("beginline"));
                    int end = Integer.parseInt(v.getAttribute("endline"));

                    fileToSmells
                            .computeIfAbsent(relPath, k -> new ArrayList<>())
                            .add(new Smell(begin, end));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return fileToSmells;
    }
}
