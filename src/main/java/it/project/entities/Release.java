package it.project.entities;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.revwalk.RevCommit;

public class Release {
    private int index;
    private final String name;
    private final LocalDateTime date;
    private final List<RevCommit> commitList;
    private List<JavaClass> javaClassList;
    private final String versionID;

    public Release(int id, String name, LocalDateTime date, String versionID) {
        this.index = id;
        this.name = name;
        this.date = date;
        this.versionID = versionID;

        commitList = new ArrayList<>();
        javaClassList = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public LocalDateTime getDate() {
        return date;
    }

    public List<RevCommit> getCommitList() {
        return commitList;
    }

    public void setJavaClassList(List<JavaClass> javaClassList) {
        this.javaClassList = javaClassList;
    }

    public List<JavaClass> getJavaClassList() {
        return javaClassList;
    }

    public String getVersionID() {
        return versionID;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Release release = (Release) o;
        // Usa un campo che è garantito essere unico per ogni release.
        // versionID è una scelta eccellente. Se non ce l'hai, usa 'name'.
        return Objects.equals(versionID, release.versionID);
    }

    @Override
    public int hashCode() {
        // Usa lo stesso campo usato in equals.
        return Objects.hash(versionID);
    }
}


