package it.project.utils;

import it.project.entities.Release;
import it.project.entities.Ticket;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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

        /* Remove ticket if: Opening version not found || Fixed Version not found ||  OV is at first release || OV after FV */
        ticketListOG.removeIf(ticket -> ticket.getOpeningVersion() == null
                || ticket.getFixedVersion() == null
                || !ticket.getOpeningVersion().getDate().isAfter(releaseList.getFirst().getDate())
                || ticket.getOpeningVersion().getDate().isAfter(ticket.getFixedVersion().getDate())
                || ticket.getOpeningVersion().getIndex() == releaseList.getFirst().getIndex());
    }

    /* Check if AV is reliable: IV is before FV || IV is before OV */

    private static void checkTicket(Ticket ticket) {
        /* Testing validity of release in affected version's list */
        if (ticket.getAffectedVersionsList().getFirst().getDate().isBefore(ticket.getResolutionDate())
                && !ticket.getAffectedVersionsList().getFirst().getDate().isAfter(ticket.getCreationDate())
                && ticket.getAffectedVersionsList().getFirst().getIndex() != ticket.getOpeningVersion().getIndex()) {
            /* Setting injected version as the first one in affected version's list */
            ticket.setInjectedVersion(ticket.getAffectedVersionsList().getFirst());
        }
    }

    public static void linkTicketsToCommits(List<Ticket> ticketList, List<Release> releaseList) {
        Logger.getAnonymousLogger().log(Level.INFO, "Starting ticket-to-commit linking process...");

        // 1. Raccogliamo tutti i commit da tutte le release in un'unica lista per efficienza.
        List<RevCommit> allCommits = new ArrayList<>();
        for (Release release : releaseList) {
            allCommits.addAll(release.getCommitList());
        }

        // 2. Iteriamo su ogni ticket per cercare corrispondenze.
        for (Ticket ticket : ticketList) {
            String ticketKey = ticket.getTicketKey(); // Es. "BOOKKEEPER-123"

            for (RevCommit commit : allCommits) {
                // 3. Controlliamo se il messaggio completo del commit contiene l'ID del ticket.
                if (commit.getFullMessage().contains(ticketKey)) {
                    // 4. Se troviamo una corrispondenza, aggiungiamo il commit alla lista del ticket.
                    //    La classe Ticket ha getCommitList(), che abbiamo inizializzato.
                    ticket.getCommitList().add(commit);
                }
            }
        }

        // 5. Log di riepilogo per verificare il risultato.
        long linkedTicketsCount = ticketList.stream().filter(t -> !t.getCommitList().isEmpty()).count();
        Logger.getAnonymousLogger().log(Level.INFO, "Linking complete. Found fixing commits for {0} out of {1} tickets.", new Object[]{linkedTicketsCount, ticketList.size()});
    }


}
