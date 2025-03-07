// License: GPL. For details, see LICENSE file.
package reverter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.conflict.ConflictAddCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.OsmTransferException;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.UserCancelException;

import reverter.ChangesetReverter.RevertType;

/**
 * The task for reverting changesets
 */
public class RevertChangesetTask extends PleaseWaitRunnable {
    private final Collection<Integer> changesetIds;
    private final RevertType revertType;
    private boolean newLayer;
    private final DataSet oldDataSet;

    private ChangesetReverter rev;
    private boolean downloadConfirmed;
    private int numberOfConflicts;

    /**
     * Create a new task for reverting a changeset with a default progress monitor and no new layer with user confirmation of the download
     * @param changesetId The changeset id to revert
     * @param revertType The type of revert to do
     */
    public RevertChangesetTask(int changesetId, RevertType revertType) {
        this(changesetId, revertType, false);
    }

    /**
     * Create a new task for reverting a changeset with a default progress monitor and no new layer
     * @param changesetId The changeset id to revert
     * @param revertType The type of revert to do
     * @param autoConfirmDownload {@code true} if the user has already indicated that they want to download missing data
     */
    public RevertChangesetTask(int changesetId, RevertType revertType, boolean autoConfirmDownload) {
        this(changesetId, revertType, autoConfirmDownload, false);
    }

    /**
     * Create a new task for reverting a changeset with a default progress monitor
     * @param changesetId The changeset id to revert
     * @param revertType The type of revert to do
            * @param autoConfirmDownload {@code true} if the user has already indicated that they want to download missing data
     * @param newLayer {@code true} if the user wants the reversion to be on a new layer
     */
    public RevertChangesetTask(int changesetId, RevertType revertType, boolean autoConfirmDownload, boolean newLayer) {
        this(Collections.singleton(changesetId), revertType, autoConfirmDownload, newLayer);
    }

    /**
     * Create a new task for reverting a changeset with a default progress monitor
     * @param changesetIds The changeset ids to revert
     * @param revertType The type of revert to do
     * @param autoConfirmDownload {@code true} if the user has already indicated that they want to download missing data
     * @param newLayer {@code true} if the user wants the reversion to be on a new layer
     */
    public RevertChangesetTask(Collection<Integer> changesetIds, RevertType revertType, boolean autoConfirmDownload, boolean newLayer) {
        this(null, changesetIds, revertType, autoConfirmDownload, newLayer);
    }

    /**
     * Create a new task for reverting a changeset
     * @param progressMonitor The {@link ProgressMonitor} to use. May be {@code null}
     * @param changesetIds The changeset ids to revert
     * @param revertType The type of revert to do
     * @param autoConfirmDownload {@code true} if the user has already indicated that they want to download missing data
     * @param newLayer {@code true} if the user wants the reversion to be on a new layer
     */
    public RevertChangesetTask(ProgressMonitor progressMonitor, Collection<Integer> changesetIds, RevertType revertType,
                               boolean autoConfirmDownload, boolean newLayer) {
        super(tr("Reverting..."), progressMonitor, false);
        this.changesetIds = new ArrayList<>(changesetIds);
        this.revertType = revertType;
        this.downloadConfirmed = autoConfirmDownload;
        this.newLayer = newLayer;
        OsmDataLayer editLayer = MainApplication.getLayerManager().getEditLayer();
        this.oldDataSet = editLayer == null ? null : editLayer.data;
    }

    private boolean checkAndDownloadMissing() throws OsmTransferException {
        if (!rev.hasMissingObjects()) return true;
        if (!downloadConfirmed) {
            final Integer selectedOption = GuiHelper.runInEDTAndWaitAndReturn(() -> JOptionPane.showConfirmDialog(MainApplication.getMainFrame(),
                    tr("This changeset has objects that are not present in current dataset.\n" +
                            "It is needed to download them before reverting. Do you want to continue?"),
                    tr("Confirm"), JOptionPane.YES_NO_OPTION));
            downloadConfirmed = selectedOption != null && selectedOption == JOptionPane.YES_OPTION;
            if (!downloadConfirmed) return false;
        }
        progressMonitor.setTicks(0);
        rev.downloadMissingPrimitives(progressMonitor.createSubTaskMonitor(ProgressMonitor.ALL_TICKS, false));
        return !progressMonitor.isCanceled();
    }

    @Override
    protected void realRun() throws OsmTransferException {
        numberOfConflicts = 0;
        final List<Command> allcmds = new ArrayList<>();
        Logging.info("Reverting {0} changeset(s): {1}",
                changesetIds.size(), changesetIds.stream().map(Long::toString).collect(Collectors.toList()));
        for (int changesetId : changesetIds) {
            try {
                Logging.info("Reverting changeset {0}", Long.toString(changesetId));
                RevertChangesetCommand cmd = revertChangeset(changesetId);
                if (cmd != null) {
                    allcmds.add(cmd);
                }
                Logging.info("Reverted changeset {0}", Long.toString(changesetId));
                newLayer = false; // reuse layer for subsequent reverts
            } catch (OsmTransferException e) {
                rollback(allcmds);
                Logging.error(e);
                throw e;
            } catch (UserCancelException e) {
                rollback(allcmds);
                GuiHelper.executeByMainWorkerInEDT(() -> new Notification(tr("Revert was canceled")).show());
                Logging.trace(e);
                return;
            }
        }
        if (!allcmds.isEmpty()) {
            Command cmd = allcmds.size() == 1 ? allcmds.get(0) : new RevertChangesetCommand(tr("Revert changesets"), allcmds);
            GuiHelper.runInEDT(() -> {
                UndoRedoHandler.getInstance().add(cmd, false);
                if (numberOfConflicts > 0) {
                    MainApplication.getMap().conflictDialog.warnNumNewConflicts(numberOfConflicts);
                }
            });
        }
    }

    private static void rollback(List<Command> allcmds) {
        if (!allcmds.isEmpty()) {
            GuiHelper.runInEDT(() -> UndoRedoHandler.getInstance().undo(allcmds.size()));
        }
    }

    private RevertChangesetCommand revertChangeset(int changesetId) throws OsmTransferException, UserCancelException {
        progressMonitor.indeterminateSubTask(tr("Reverting changeset {0}", Long.toString(changesetId)));
        try {
            rev = new ChangesetReverter(changesetId, revertType, newLayer, oldDataSet, progressMonitor.createSubTaskMonitor(0, true));
        } catch (final RevertRedactedChangesetException e) {
            Logging.debug(e);
            GuiHelper.runInEDT(() -> new Notification(
                    e.getMessage()+"<br>"+
                    tr("See {0}", "<a href=\"https://www.openstreetmap.org/redactions\">https://www.openstreetmap.org/redactions</a>"))
            .setIcon(JOptionPane.ERROR_MESSAGE)
            .setDuration(Notification.TIME_LONG)
            .show());
            progressMonitor.cancel();
        }
        if (progressMonitor.isCanceled())
            throw new UserCancelException();
        int numOldConflicts = oldDataSet == null ? 0 : oldDataSet.getConflicts().size();
        // Check missing objects
        rev.checkMissingCreated();
        rev.checkMissingUpdated();
        if (rev.hasMissingObjects()) {
            // If missing created or updated objects, ask user
            rev.checkMissingDeleted();
            if (!checkAndDownloadMissing())
                throw new UserCancelException();
        } else {
            // Don't ask user to download primitives going to be undeleted
            rev.checkMissingDeleted();
            rev.downloadMissingPrimitives(progressMonitor.createSubTaskMonitor(ProgressMonitor.ALL_TICKS, false));
        }
        int numConflicts = oldDataSet == null ? 0 : oldDataSet.getConflicts().size();
        if (numConflicts > numOldConflicts) {
            GuiHelper.runInEDT(() -> new Notification(tr("Please solve conflicts and maybe try again to revert."))
            .setIcon(JOptionPane.ERROR_MESSAGE)
            .show());
            return null;
        }

        if (progressMonitor.isCanceled())
            throw new UserCancelException();
        progressMonitor.setTicks(0);
        rev.downloadObjectsHistory(progressMonitor.createSubTaskMonitor(ProgressMonitor.ALL_TICKS, false));
        if (progressMonitor.isCanceled())
            throw new UserCancelException();
        if (!checkAndDownloadMissing())
            throw new UserCancelException();
        progressMonitor.setTicks(0);
        rev.fixNodesWithoutCoordinates(progressMonitor.createSubTaskMonitor(ProgressMonitor.ALL_TICKS, false));
        if (progressMonitor.isCanceled())
            throw new UserCancelException();
        List<Command> cmds = rev.getCommands();
        if (cmds.isEmpty()) {
            Logging.warn(MessageFormat.format("No revert commands found for changeset {0}", Long.toString(changesetId)));
            return null;
        }
        GuiHelper.runInEDT(() -> executeCommands(cmds));
        final String desc = getRevertDescription(revertType, changesetId);
        return new RevertChangesetCommand(desc, cmds);
    }

    /**
     * Execute a list of commands
     * @param cmds The commands to execute
     */
    private void executeCommands(List<Command> cmds) {
        cmds.get(0).getAffectedDataSet().update(() -> {
            for (Command c : cmds) {
                if (c instanceof ConflictAddCommand) {
                    numberOfConflicts++;
                }
                c.executeCommand();
            }
        });
    }

    /**
     * Get the description to show the user for this reversion
     * @param revertType The type of revert
     * @param changesetId The changeset id
     * @return The string to show the user
     */
    private static String getRevertDescription(RevertType revertType, int changesetId) {
        if (revertType == RevertType.FULL) {
            return tr("Revert changeset {0}", String.valueOf(changesetId));
        } else {
            return tr("Partially revert changeset {0}", String.valueOf(changesetId));
        }
    }

    @Override
    protected void cancel() {
        // nothing to do
    }

    @Override
    protected void finish() {
        // nothing to do

    }

    /**
     * Return number of conflicts for this changeset.
     * @return number of conflicts for this changeset
     */
    public final int getNumberOfConflicts() {
        return numberOfConflicts;
    }
}
