package it.project.utils;

import it.project.entities.Release;
import it.project.entities.Ticket;

import java.util.ArrayList;
import java.util.List;

public class TicketUtils {
    private TicketUtils() {}

    public static void fixInconsistentTickets(List<Ticket> ticketListOG, List<Release> releaseList) {
        List<Ticket> ticketListOT = new ArrayList<>();

        for (Ticket ticket : ticketListOG) {
            /* Check if affected version's list is not empty */
            if (!ticket.getAffectedVersionsList().isEmpty() && ticket.getOpeningVersion() != null && ticket.getFixedVersion() != null) {
                checkTicket(ticket);
                if (ticket.getAffectedVersionsList().getFirst().getDate().isAfter(ticket.getCreationDate()))
                    ticketListOT.add(ticket);
            }
        }

        for (Ticket ticket : ticketListOT) {
            ticketListOG.remove(ticket);
        }

        /* Remove ticket if:
        * 1) Opening version not found;
        * 2) Fixed Version not found;
        * 3) OV is at first release;
        * 4) OV after FV: is a paradox;
         */

        ticketListOG.removeIf(ticket -> ticket.getOpeningVersion() == null
                || ticket.getFixedVersion() == null
                || !ticket.getOpeningVersion().getDate().isAfter(releaseList.getFirst().getDate())
                || ticket.getOpeningVersion().getDate().isAfter(ticket.getFixedVersion().getDate())
                || ticket.getOpeningVersion().getIndex() == releaseList.getFirst().getIndex());
    }

    /* Check if AV is reliable
    * 1) IV is before FV
    * 2) IV is before OV
     */
    private static void checkTicket(Ticket ticket) {
        /* Testing validity of release in affected version's list */
        if (ticket.getAffectedVersionsList().getFirst().getDate().isBefore(ticket.getResolutionDate())
                && !ticket.getAffectedVersionsList().getFirst().getDate().isAfter(ticket.getCreationDate())
                && ticket.getAffectedVersionsList().getFirst().getIndex() != ticket.getOpeningVersion().getIndex()) {
            /* Setting injected version as the first one in affected version's list */
            ticket.setInjectedVersion(ticket.getAffectedVersionsList().getFirst());
        }
    }



}
