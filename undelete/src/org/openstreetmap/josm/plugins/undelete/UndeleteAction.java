// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.undelete;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.AutoScaleAction.AutoScaleMode;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.NodeData;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.RelationMemberData;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.history.History;
import org.openstreetmap.josm.data.osm.history.HistoryDataSet;
import org.openstreetmap.josm.data.osm.history.HistoryNode;
import org.openstreetmap.josm.data.osm.history.HistoryOsmPrimitive;
import org.openstreetmap.josm.data.osm.history.HistoryRelation;
import org.openstreetmap.josm.data.osm.history.HistoryWay;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.history.HistoryLoadTask;
import org.openstreetmap.josm.gui.io.DownloadPrimitivesTask;
import org.openstreetmap.josm.gui.io.DownloadPrimitivesWithReferrersTask;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Undelete one or more objects.
 */
public class UndeleteAction extends JosmAction {

    private final class Worker implements Runnable {
        private final OsmPrimitive parent;

        private final OsmDataLayer layer;

        private final List<PrimitiveId> ids;

        private Set<OsmPrimitive> restored;

        private Set<PrimitiveId> missingPrimitives;

        private Worker(OsmPrimitive parent, OsmDataLayer layer, List<PrimitiveId> ids, Set<OsmPrimitive> restored,
                HistoryLoadTask task) {
            this.parent = parent;
            this.layer = layer;
            this.ids = ids;
            this.restored = restored;
            this.missingPrimitives = task.getMissingPrimitives();
        }

        @Override
        public void run() {
            List<Node> nodes = new ArrayList<>();
            for (PrimitiveId pid : ids) {
                if (missingPrimitives == null || missingPrimitives.contains(pid)) {
                    continue;
                }

                OsmPrimitive primitive = layer.data.getPrimitiveById(pid);
                if (primitive == null) {
                    try {
                        final Long id = pid.getUniqueId();
                        final OsmPrimitiveType type = pid.getType();

                        History h = HistoryDataSet.getInstance().getHistory(id, type);

                        if (h == null) {
                            Logging.warn("Cannot find history for " + type + " " + id);
                            return;
                        }

                        HistoryOsmPrimitive hPrimitive1 = h.getLatest();

                        boolean visible = hPrimitive1.isVisible();

                        if (visible) {
                            if (type == OsmPrimitiveType.NODE) {
                                // we have all needed information in the history object
                                primitive = new Node(hPrimitive1.getId());
                                HistoryNode hNode = (HistoryNode) hPrimitive1;
                                primitive.load(hNode.fillPrimitiveData(new NodeData()));
                                layer.data.addPrimitive(primitive);
                            } else {
                                // If the way or relation is not deleted we get the real object
                                DownloadPrimitivesTask download = new DownloadPrimitivesTask(layer, Collections.singletonList(pid), true);
                                download.setZoom(false);
                                download.run();
                                primitive = layer.data.getPrimitiveById(id, type);
                            }
                            restored.add(primitive);
                        } else {
                            // We search latest visible version < n with redaction robustness
                            long idx = 1;
                            long n = hPrimitive1.getVersion();
                            HistoryOsmPrimitive hPrimitive2 = null;
                            while (idx < n) {
                                hPrimitive2 = h.getByVersion(n - idx++);
                                if (hPrimitive2 != null && hPrimitive2.isVisible()) {
                                    // don't restore an invisible object
                                    break;
                                }
                            }
                            if (type == OsmPrimitiveType.NODE) {
                                // We get version and user from the latest version,
                                // coordinates and tags from latest visible version < n
                                Node node = new Node(id, (int) hPrimitive1.getVersion());

                                HistoryNode hNode = (HistoryNode) hPrimitive2;
                                if (hNode != null) {
                                    node.setCoor(hNode.getCoords());
                                }

                                primitive = node;
                            } else if (type == OsmPrimitiveType.WAY) {
                                // We get version and user from the latest version,
                                // nodes and tags from latest visible version < n
                                hPrimitive1 = h.getLatest();

                                Way way = new Way(id, (int) hPrimitive1.getVersion());

                                HistoryWay hWay = (HistoryWay) hPrimitive2;
                                List<PrimitiveId> nodeIds = new ArrayList<>();
                                if (hWay != null) {
                                    for (Long i : hWay.getNodes()) {
                                        nodeIds.add(new SimplePrimitiveId(i, OsmPrimitiveType.NODE));
                                    }
                                }
                                undelete(false, nodeIds, way, restored);

                                primitive = way;
                            } else {
                                primitive = new Relation();
                                hPrimitive1 = h.getLatest();

                                Relation rel = new Relation(id, (int) hPrimitive1.getVersion());
                                HistoryRelation hRel = (HistoryRelation) hPrimitive2;

                                if (hRel != null) {
                                    List<RelationMember> members = new ArrayList<>(hRel.getNumMembers());
                                    for (RelationMemberData m : hRel.getMembers()) {
                                        OsmPrimitive p = layer.data.getPrimitiveById(m.getMemberId(), m.getMemberType());
                                        if (p == null) {
                                            switch (m.getMemberType()) {
                                            case NODE:
                                                p = new Node(m.getMemberId());
                                                break;
                                            case CLOSEDWAY:
                                            case WAY:
                                                p = new Way(m.getMemberId());
                                                break;
                                            case MULTIPOLYGON:
                                            case RELATION:
                                                p = new Relation(m.getMemberId());
                                                break;
                                            }
                                            layer.data.addPrimitive(p);
                                            restored.add(p);
                                        }
                                        members.add(new RelationMember(m.getRole(), p));
                                    }
                                    rel.setMembers(members);
                                }
                                primitive = rel;
                            }

                            if (hPrimitive2 != null) {
                                primitive.setChangesetId((int) hPrimitive1.getChangesetId());
                                primitive.setInstant(hPrimitive1.getInstant());
                                primitive.setUser(hPrimitive1.getUser());
                                primitive.setVisible(hPrimitive1.isVisible());
                                primitive.setKeys(hPrimitive2.getTags());
                                primitive.setModified(true);

                                layer.data.addPrimitive(primitive);
                                restored.add(primitive);
                            }
                        }
                    } catch (Exception e) {
                        Logging.error(e);
                    }
                }
                if (parent != null && primitive instanceof Node) {
                    nodes.add((Node) primitive);
                }
            }
            if (parent instanceof Way && !nodes.isEmpty()) {
                ((Way) parent).setNodes(nodes);
            }
            if (!restored.isEmpty()) {
                GuiHelper.runInEDT(() -> {
                    layer.data.setSelected(restored);
                    AutoScaleAction.autoScale(AutoScaleMode.SELECTION);
                });
            }
            if (missingPrimitives != null && !missingPrimitives.isEmpty()) {
                GuiHelper.runInEDTAndWait(() -> DownloadPrimitivesWithReferrersTask.reportProblemDialog(missingPrimitives,
                        trn("Object could not be undeleted", "Some objects could not be undeleted", missingPrimitives.size()),
                        trn("One object could not be undeleted.<br>",
                                "{0} objects could not be undeleted.<br>",
                                missingPrimitives.size(),
                                missingPrimitives.size())
                                + tr("The server replied with response code 404.<br>"
                                     + "This usually means, the server does not know an object with the requested id. Maybe it was redacted."),
                        tr("missing objects:"),
                        JOptionPane.ERROR_MESSAGE
                        ).showDialog());

            }
        }

    }

    /**
     * Create undelete action.
     */
    public UndeleteAction() {
		super(tr("Undelete object..."), "undelete", tr("Undelete object by id"),
				Shortcut.registerShortcut("tools:undelete", tr("File: {0}", tr("Undelete object...")), KeyEvent.VK_U,
						Shortcut.ALT_SHIFT),
				true, false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        UndeleteDialog dialog = new UndeleteDialog(MainApplication.getMainFrame());
        if (dialog.showDialog().getValue() != 1)
            return;
        Config.getPref().putBoolean("undelete.newlayer", dialog.isNewLayerSelected());
        Config.getPref().put("undelete.osmid", dialog.getOsmIdsString());
        undelete(dialog.isNewLayerSelected(), dialog.getOsmIds(), null);
    }

    public void undelete(boolean newLayer, final List<PrimitiveId> ids, final OsmPrimitive parent) {
        undelete(newLayer, ids, parent, new LinkedHashSet<>());
    }

    private void undelete(boolean newLayer, final List<PrimitiveId> ids, final OsmPrimitive parent, Set<OsmPrimitive> restored) {

        // TODO: undelete relation members if necessary
        Logging.info("Undeleting "+ids+(parent == null ? "" : " with parent "+parent));

        OsmDataLayer tmpLayer = MainApplication.getLayerManager().getEditLayer();
        if ((tmpLayer == null) || newLayer) {
            tmpLayer = new OsmDataLayer(new DataSet(), OsmDataLayer.createNewName(), null);
            MainApplication.getLayerManager().addLayer(tmpLayer);
        }

        final OsmDataLayer layer = tmpLayer;

        HistoryLoadTask task = new HistoryLoadTask();
        task.setChangesetDataNeeded(false);
        task.setCollectMissing(true);
        for (PrimitiveId id : ids) {
            task.add(id);
        }

        MainApplication.worker.execute(task);
        MainApplication.worker.submit(new Worker(parent, layer, ids, restored, task));
    }
}
