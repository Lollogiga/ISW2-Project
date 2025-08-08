package it.project.utils;

import it.project.entities.Smell;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PmdParser {

    public Map<String, List<Smell>> parseReport(File reportFile, File repoRoot) {
        Map<String, List<Smell>> fileToSmells = new HashMap<>();

        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(reportFile);
            doc.getDocumentElement().normalize();

            NodeList fileNodes = doc.getElementsByTagName("file");

            for (int i = 0; i < fileNodes.getLength(); i++) {
                Element fileElement = (Element) fileNodes.item(i);
                String absPath = fileElement.getAttribute("name");
                String relPath = getRelativePath(repoRoot, absPath);

                NodeList violations = fileElement.getElementsByTagName("violation");

                for (int j = 0; j < violations.getLength(); j++) {
                    Element violation = (Element) violations.item(j);
                    int begin = Integer.parseInt(violation.getAttribute("beginline"));
                    int end = Integer.parseInt(violation.getAttribute("endline"));

                    fileToSmells
                            .computeIfAbsent(relPath, k -> new ArrayList<>())
                            .add(new Smell(begin, end));
                }
            }
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "PMD parsing error: {0}", e.getMessage());

        }

        return fileToSmells;
    }

    private String getRelativePath(File repoRoot, String absPath) {
        File absFile = new File(absPath);
        return repoRoot.toURI().relativize(absFile.toURI()).getPath();
    }
}
