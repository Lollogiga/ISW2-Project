package it.project.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DetectWalkPass {

    private String projName;

    public DetectWalkPass(String projName) {
        this.projName = projName;
    }

    public int detectWalkPass() throws IOException {
        // Componi il path in modo portabile
        Path testingDir = Paths.get(
                "src", "main", "resources",
                projName.toLowerCase(),
                "testing", "ARFF"
        );

        if (!Files.isDirectory(testingDir)) {
            throw new IllegalStateException("Directory inesistente: " + testingDir.toAbsolutePath());
        }


        Pattern p = Pattern.compile(
                Pattern.quote(projName) + "_testing_iter_(\\d+)\\.arff"
        );

        int maxIter = 0;
        try (var stream = Files.list(testingDir)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
                String name = f.getFileName().toString();
                Matcher m = p.matcher(name);
                if (m.matches()) {
                    int iter = Integer.parseInt(m.group(1));
                    if (iter > maxIter) {
                        maxIter = iter;
                    }
                }
            }
        }

        if (maxIter == 0) {
            throw new IllegalStateException(
                    "Nessun file trovato con pattern \"" + projName + "_testing_iter_<i>.arff\" in " + testingDir.toAbsolutePath()
            );
        }

        return maxIter;
    }
}
