package edu.byu.ece.rapidSmith.route.examples;

import edu.byu.ece.rapidSmith.cad.route.RSVRoute;
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CadException;
import edu.byu.ece.rapidSmith.design.subsite.*;
import edu.byu.ece.rapidSmith.device.Device;
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoCheckpoint;
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface;
import edu.byu.ece.rapidSmith.interfaces.vivado.XdcConstraint;

import java.io.IOException;
import java.util.*;

public class RSVRouteExample {
    private static CellDesign design;
    private static Device device;
    private static CellLibrary libCells;

    /**
     * Removes HLUTNM, SOFT_HLUTNM, and LUTNM properties from a netlist.
     */
    private static void removeLutPairs() {
        Iterator<Cell> cellIt = design.getLeafCells().filter(Cell::isLut).iterator();
        while (cellIt.hasNext()) {
            Cell lutCell = cellIt.next();
            lutCell.getProperties().remove("LUTNM");
            lutCell.getProperties().remove("HLUTNM");
            lutCell.getProperties().remove("SOFT_HLUTNM");
        }
    }

    /**
     * Sets Vivado's DRC checks that make sure ports are constrained and have specified I/O standards to be
     * warnings instead of errors.
     */
    private static void disablePortDRC() {
        design.addVivadoConstraint(new XdcConstraint("set_property", "SEVERITY {Warning} [get_drc_checks NSTD-1]"));
        design.addVivadoConstraint(new XdcConstraint("set_property", "SEVERITY {Warning} [get_drc_checks UCIO-1]"));
    }

    /**
     * Sets all the cells and nets in the design to DONT_TOUCH. This must be done instead of simply setting the entire
     * design to DONT_TOUCH to ensure the "update_design -cells blackbox_cell -from_file rm_netlist.edf" TCL command
     * does not do any optimizations on the netlist.
     */
    private static void dontTouchEdif() {
        // Set all cells to DONT_TOUCH
        for (Cell cell : design.getCells()) {
            if (!cell.getProperties().has("DONT_TOUCH"))
                cell.getProperties().add(new Property("DONT_TOUCH", PropertyType.EDIF, "TRUE"));
        }

        // Set all nets to DONT_TOUCH
        for (CellNet net : design.getNets()) {
            if (!net.getProperties().has("DONT_TOUCH"))
                net.getProperties().add(new Property("DONT_TOUCH", PropertyType.EDIF, "TRUE"));
        }
    }

    private static void importDesign(String checkpointIn) throws IOException {
        VivadoCheckpoint vcp = VivadoInterface.loadRSCP(checkpointIn, true, true);

        // Get the pieces out of the checkpoint for use in manipulating it
        design = vcp.getDesign();
        device = vcp.getDevice();
        libCells = vcp.getLibCells();
    }

    private static void exportDesign(String checkpointOut) throws IOException {
        // Prepare to export the TCP
        removeLutPairs();
        disablePortDRC();
        dontTouchEdif();
        VivadoInterface.writeTCP(checkpointOut, design, device, libCells, true);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: RSVRouteExample rscpCheckpointDirectoryName");
            System.exit(1);
        }
        String checkpointIn = args[0];
        String tcpOut = checkpointIn.substring(0, checkpointIn.length() - 4) + "tcp";

        // Import a placed design
        importDesign(checkpointIn);

        // Route the design with RSVRoute
        RSVRoute router = new RSVRoute(device, design, libCells, true);
        try {
            router.routeDesign();
        } catch (CadException e) {
            e.printStackTrace();
        }

        // Export the design
        exportDesign(tcpOut);
    }
}