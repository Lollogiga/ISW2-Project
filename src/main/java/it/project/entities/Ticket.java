package it.project.entities;

import org.eclipse.jgit.revwalk.RevCommit;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Ticket {
    private final LocalDateTime creationDate;
    private LocalDateTime resolutionDate;
    private Release injectedVersion;
    private Release openingVersion;
    private Release fixedVersion;
    private List<Release> affectedVersionsList;
    private final String ticketKey;
    private List<RevCommit> commitList;

    public Ticket(LocalDateTime creationDate, LocalDateTime resolutionDate, String ticketKey) {
        this.creationDate = creationDate;
        this.resolutionDate = resolutionDate;
        this.ticketKey = ticketKey;

        this.affectedVersionsList = new ArrayList<>();
        this.commitList = new ArrayList<>();
    }

    public LocalDateTime getCreationDate() {
        return creationDate;
    }

    public LocalDateTime getResolutionDate() {
        return resolutionDate;
    }

    public String getTicketKey() {
        return ticketKey;
    }

    public Release getFixedVersion() {
        return fixedVersion;
    }

    public List<Release> getAffectedVersionsList() {
        return affectedVersionsList;
    }

    public Release getInjectedVersion() {
        return injectedVersion;
    }

    public Release getOpeningVersion() {
        return openingVersion;
    }

    public void setInjectedVersion(Release injectedVersion) {
        this.injectedVersion = injectedVersion;
    }

    public void setAffectedVersionsList(List<Release> affectedVersionsList) {
        this.affectedVersionsList = affectedVersionsList;
    }

    public void setOpeningVersion(Release openingVersion) {
        this.openingVersion = openingVersion;
    }

    public void setFixedVersion(Release fixedVersion) {
        this.fixedVersion = fixedVersion;
    }

    public void setCommitList(List<RevCommit> commitList) {
        this.commitList = commitList;
    }

    public List<RevCommit> getCommitList() {
        return commitList;
    }

    public void setResolutionDate(LocalDateTime resolutionDate) {
        this.resolutionDate = resolutionDate;
    }
}