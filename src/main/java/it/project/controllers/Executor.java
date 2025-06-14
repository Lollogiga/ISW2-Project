package it.project.controllers;

import it.project.entities.Release;
import it.project.entities.Ticket;
import it.project.utils.FileCSVGenerator;
import it.project.utils.TicketUtils;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Executor {
    private static final String DIRECTORY = "src/main/resources/";

    private Executor() {}

    public static void dataExtraction(String projectName) throws IOException {
        JiraExtraction jira = new JiraExtraction(projectName);
        FileCSVGenerator csv = new FileCSVGenerator(DIRECTORY, projectName);


        List<Release> releaseList = jira.getReleaseInfo();
        Logger.getAnonymousLogger() .log(Level.INFO, "Data extraction: Releases List");

        //Generiamo il file csv relativo alle release estratte da jira
        csv.generateReleaseInfo(releaseList);

        List<Ticket> ticketList = jira.fetchTickets(releaseList);
        Logger.getAnonymousLogger() .log(Level.INFO, "Data extraction: Tickets List");
        //Remove all ticket not reliable
        TicketUtils.fixInconsistentTickets(ticketList, releaseList);
        //Order data by resolution date:
        ticketList.sort(Comparator.comparing(Ticket::getResolutionDate));


    }
}
