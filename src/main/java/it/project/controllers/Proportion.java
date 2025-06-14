package it.project.controllers;

import it.project.entities.Release;
import it.project.entities.Ticket;
import it.project.utils.ProjectNamesEnum;
import it.project.utils.TicketUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Proportion {
   private static final int THRESHOLD = 5;

   private Proportion(){}

    /* Proportion formula used: P = (FV-IV)/(FV-OV) */
    public static void calculateProportion(List<Ticket> fixedTicketList, List<Release> releaseList) throws IOException {
        List<Ticket> ticketListToProportion = new ArrayList<>();    // list to put ticket used with proportion
        float pColdStart = coldStartProportion();

        for (Ticket ticket : fixedTicketList) {
            /* Check if ticket has an injected version
            * If it has IV: we can use it for estimate ticket without IV
            * Else it hasn't: we can use proportion for estimate IV
             */
            if (ticket.getInjectedVersion() != null) {
                ticketListToProportion.add(ticket);
            }
            /* Compute proportion */
            else {
                computeProportion(ticket, ticketListToProportion, releaseList, pColdStart);
            }
        }
    }

    private static void computeProportion(Ticket ticket, List<Ticket> ticketListProp, List<Release> releaseList, float pColdStart) {
        float p;

        //If there aren't enough ticket with IV, use cold start for estimate IV
        if (ticketListProp.size() < THRESHOLD) {
            p = pColdStart;
        }
        //Else use increment for estimate IV
        else {
            p = incrementProportion(ticketListProp);
        }
        settingIV(ticket, releaseList, p);
        settingAV(ticket, releaseList);
    }

    //Set the affected version of the ticket:
    private static void settingAV(Ticket ticket, List<Release> releaseList) {
        List<Release> tempAV = new ArrayList<>();

        for (int i = ticket.getInjectedVersion().getIndex(); i < ticket.getFixedVersion().getIndex(); i++) {
            tempAV.add(releaseList.get(i-1));
        }

        ticket.setAffectedVersionsList(tempAV);
    }

    //Set the version when the bug is injected: we use P for estimate IV
    private static void settingIV(Ticket ticket, List<Release> releaseList, float p) {
        int iv;

        if (ticket.getOpeningVersion().getIndex() == ticket.getFixedVersion().getIndex()
                && ticket.getInjectedVersion() == null) {
            iv = (int) (ticket.getFixedVersion().getIndex() - p);
        } else {
            /* IV=FV-((FV-OV)*P) */
            iv = (int) (ticket.getFixedVersion().getIndex()-((ticket.getFixedVersion().getIndex()-ticket.getOpeningVersion().getIndex())*p));
        }

        if (iv < 1.0) {
            iv = 1;
        }

        ticket.setInjectedVersion(releaseList.get(iv-1));
    }

    //We use increment for estimate P (we use the IV of other ticket)
    private static float incrementProportion(List<Ticket> list) {
        List<Float> proportionValue = new ArrayList<>();
        float pIncrement;
        float p;
        float pSum = 0;

        for (Ticket ticket : list) {
            p = computeP(ticket);
            proportionValue.add(p);
        }

        for (Float pValue : proportionValue) {
            pSum += pValue;
        }
        pIncrement = pSum/(proportionValue.size());

        return pIncrement;
    }

    //Compute P:
    private static float computeP(Ticket ticket) {
        /* Check if FV=OV then set FV-OV=1 */
        if (ticket.getOpeningVersion().getIndex() == ticket.getFixedVersion().getIndex()) {
            return (ticket.getFixedVersion().getIndex() - ticket.getInjectedVersion().getIndex());
        }

        /* General case */
        return (ticket.getFixedVersion().getIndex() - ticket.getInjectedVersion().getIndex()) * 1.0f/(ticket.getFixedVersion().getIndex()-ticket.getOpeningVersion().getIndex());
    }

    private static float coldStartProportion() throws IOException {
        List<Float> proportionValueProjects = new ArrayList<>();    // List for other projects
        float p;
        float pColdStart;

        for (ProjectNamesEnum name : ProjectNamesEnum.values()) {
            float pSum = 0;
            List<Float> proportionValue = new ArrayList<>();    // List for single project

            JiraExtraction jira = new JiraExtraction(name.toString());

            List<Release> releaseList = jira.getReleaseInfo();  // fetch all project's releases

            List<Ticket> ticketList = jira.fetchTickets(releaseList);  // fetch all project's list
            TicketUtils.fixInconsistentTickets(ticketList, releaseList);  // fix tickets inconsistency
            ticketList.removeIf(ticket -> ticket.getInjectedVersion() == null);

            for (Ticket ticket : ticketList) {
                p = computeP(ticket);
                proportionValue.add(p);
            }

            for (Float pValue : proportionValue) {
                pSum += pValue;
            }

            proportionValueProjects.add(pSum/(proportionValue.size()));
        }

        float pSum = 0;
        for (Float P_valueTotal : proportionValueProjects) {
            pSum += P_valueTotal;
        }
        pColdStart = pSum/(proportionValueProjects.size());

        return pColdStart;
    }



}
