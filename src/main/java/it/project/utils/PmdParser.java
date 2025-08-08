package it.project.utils;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

public class PmdParser {

    public Map<String, List<String>> parseReport(File xmlFile) {
        Map<String, List<String>> smellsByMethod = new HashMap<>();

        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile);
            doc.getDocumentElement().normalize();

            NodeList violations = doc.getElementsByTagName("violation");

            for (int i = 0; i < violations.getLength(); i++) {
                Element violation = (Element) violations.item(i);
                String absolutePath = ((Element) violation.getParentNode()).getAttribute("name");
                String rule = violation.getAttribute("rule");

                int beginLine = Integer.parseInt(violation.getAttribute("beginline"));
                int endLine = Integer.parseInt(violation.getAttribute("endline"));

                // ✅ Path assoluto (da XML) → normalizzato rispetto alla root del repository
                File absoluteFile = new File(absolutePath);
                File repoRoot = xmlFile.getParentFile().getParentFile(); // es: tmp/bookkeeper

                String normalizedPath = repoRoot.toURI().relativize(absoluteFile.toURI()).getPath();

                // Chiave compatibile con quella usata in GitExtraction
                String key = normalizedPath + "::" + beginLine + "-" + endLine;

                smellsByMethod.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
            }

        } catch (Exception e) {
            System.err.println("Errore durante il parsing del report PMD: " + e.getMessage());
        }

        return smellsByMethod;
    }
}
