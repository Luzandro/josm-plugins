// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.turnrestrictions.editor;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

/**
 * TurnRestrictionEditorManager keeps track of the open turn restriction editors.
 *
 */
public class TurnRestrictionEditorManager extends WindowAdapter implements LayerChangeListener {
    //private static final Logger logger = Logger.getLogger(TurnRestrictionEditorManager.class.getName());

    /** keeps track of open relation editors */
    static TurnRestrictionEditorManager instance;

    /**
     * Replies the singleton {@link TurnRestrictionEditorManager}
     *
     * @return the singleton {@link TurnRestrictionEditorManager}
     */
    public static TurnRestrictionEditorManager getInstance() {
        if (TurnRestrictionEditorManager.instance == null) {
            TurnRestrictionEditorManager.instance = new TurnRestrictionEditorManager();
            MainApplication.getLayerManager().addLayerChangeListener(TurnRestrictionEditorManager.instance);
        }
        return TurnRestrictionEditorManager.instance;
    }

    /**
     * Helper class for keeping the context of a turn restriction editor. A turn
     * restriction editor is open for turn restriction in a  {@link OsmDataLayer}
     */
    private static class DialogContext {
        public final PrimitiveId primitiveId;
        public final OsmDataLayer layer;

        DialogContext(OsmDataLayer layer, PrimitiveId id) {
            this.layer = layer;
            this.primitiveId = id;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((layer == null) ? 0 : layer.hashCode());
            result = prime * result
                    + ((primitiveId == null) ? 0 : primitiveId.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof DialogContext))
                return false;
            DialogContext other = (DialogContext) obj;
            if (layer == null) {
                if (other.layer != null)
                    return false;
            } else if (!layer.equals(other.layer))
                return false;
            if (primitiveId == null) {
                if (other.primitiveId != null)
                    return false;
            } else if (!primitiveId.equals(other.primitiveId))
                return false;
            return true;
        }

        public boolean matchesLayer(OsmDataLayer layer) {
            if (layer == null) return false;
            return this.layer.equals(layer);
        }

        @Override
        public String toString() {
            return "[Context: layer=" + layer.getName() + ",relation=" + primitiveId + "]";
        }
    }

    /** the map of open dialogs */
    private final HashMap<DialogContext, TurnRestrictionEditor> openDialogs = new HashMap<>();

    /**
     * constructor
     */
    public TurnRestrictionEditorManager() {}

    /**
     * Register the editor for a turn restriction managed by a
     * {@link OsmDataLayer}.
     *
     * @param layer the layer
     * @param relation the turn restriction
     * @param editor the editor
     */
    public void register(OsmDataLayer layer, Relation relation, TurnRestrictionEditor editor) {
        if (relation == null) {
            relation = new Relation();
        }
        DialogContext context = new DialogContext(layer, relation.getPrimitiveId());
        openDialogs.put(context, editor);
        editor.addWindowListener(this);
    }

    public void updateContext(OsmDataLayer layer, Relation relation, TurnRestrictionEditor editor) {
        // lookup the entry for editor and remove it
        //
        for (DialogContext context: openDialogs.keySet()) {
            if (openDialogs.get(context) == editor) {
                openDialogs.remove(context);
                break;
            }
        }
        // don't add a window listener. Editor is already known to the relation dialog manager
        //
        DialogContext context = new DialogContext(layer, relation.getPrimitiveId());
        openDialogs.put(context, editor);
    }

    /**
     * Closes the editor open for a specific layer and a specific relation.
     *
     * @param layer  the layer
     * @param relation the relation
     */
    public void close(OsmDataLayer layer, Relation relation) {
        DialogContext context = new DialogContext(layer, relation);
        TurnRestrictionEditor editor = openDialogs.get(context);
        if (editor != null) {
            editor.setVisible(false);
        }
    }

    /**
     * Replies true if there is an open turn restriction editor for the turn
     * restriction managed
     * by the given layer. Replies false if relation is null.
     *
     * @param layer  the layer
     * @param relation  the turn restriction. May be null.
     * @return true if there is an open turn restriction editor for the turn restriction managed
     * by the given layer; false otherwise
     */
    public boolean isOpenInEditor(OsmDataLayer layer, Relation relation) {
        if (relation == null) return false;
        DialogContext context = new DialogContext(layer, relation.getPrimitiveId());
        return openDialogs.keySet().contains(context);

    }

    /**
     * Replies the editor for the turn restriction managed by layer. Null, if no such editor
     * is currently open. Returns null, if relation is null.
     *
     * See also {@link #isOpenInEditor(OsmDataLayer, Relation)}
     *
     * @param layer the layer
     * @param relation the relation
     * @return the editor for the turn restriction managed by layer. Null, if no such editor
     * is currently open.
     */
    public TurnRestrictionEditor getEditorForRelation(OsmDataLayer layer, Relation relation) {
        if (relation == null) return null;
        DialogContext context = new DialogContext(layer, relation.getPrimitiveId());
        return openDialogs.get(context);
    }

    @Override
    public void windowClosed(WindowEvent e) {
        TurnRestrictionEditor editor = (TurnRestrictionEditor) e.getWindow();
        DialogContext context = null;
        for (DialogContext c : openDialogs.keySet()) {
            if (editor.equals(openDialogs.get(c))) {
                context = c;
                break;
            }
        }
        if (context != null) {
            openDialogs.remove(context);
        }
    }

    /**
     * Positions an {@link TurnRestrictionEditor} centered on the screen
     *
     * @param editor the editor
     */
    protected void centerOnScreen(TurnRestrictionEditor editor) {
        Point p = new Point(0, 0);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        p.x = (d.width - editor.getSize().width)/2;
        p.y = (d.height - editor.getSize().height)/2;
        p.x = Math.max(p.x, 0);
        p.y = Math.max(p.y, 0);
        editor.setLocation(p);
    }

    /**
     * Replies true, if there is another open {@link TurnRestrictionEditor} whose
     * upper left corner is close to <code>p</code>.
     *
     * @param p  the reference point to check
     * @return true, if there is another open {@link TurnRestrictionEditor} whose
     * upper left corner is close to <code>p</code>.
     */
    protected boolean hasEditorWithCloseUpperLeftCorner(Point p) {
        for (TurnRestrictionEditor editor: openDialogs.values()) {
            Point corner = editor.getLocation();
            if (p.x >= corner.x -5 && corner.x + 5 >= p.x
                    && p.y >= corner.y -5 && corner.y + 5 >= p.y)
                return true;
        }
        return false;
    }

    /**
     * Positions a {@link TurnRestrictionEditor} close to the center of the screen, in such
     * a way, that it doesn't entirely cover another {@link TurnRestrictionEditor}
     */
    protected void positionCloseToScreenCenter(TurnRestrictionEditor editor) {
        Point p = new Point(0, 0);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        p.x = (d.width - editor.getSize().width)/2;
        p.y = (d.height - editor.getSize().height)/2;
        p.x = Math.max(p.x, 0);
        p.y = Math.max(p.y, 0);
        while (hasEditorWithCloseUpperLeftCorner(p)) {
            p.x += 20;
            p.y += 20;
        }
        editor.setLocation(p);
    }

    /**
     * Positions a {@link TurnRestrictionEditor} on the screen. Tries to center it on the
     * screen. If it hides another instance of an editor at the same position this
     * method tries to reposition <code>editor</code> by moving it slightly down and
     * slightly to the right.
     *
     * @param editor the editor
     */
    public void positionOnScreen(TurnRestrictionEditor editor) {
        if (editor == null) return;
        if (openDialogs.isEmpty()) {
            centerOnScreen(editor);
        } else {
            positionCloseToScreenCenter(editor);
        }
    }

    /* ----------------------------------------------------------------------------------- */
    /* interface LayerChangeListener                                               */
    /* ----------------------------------------------------------------------------------- */

    @Override
    public void layerRemoving(LayerRemoveEvent e) {
        Layer oldLayer = e.getRemovedLayer();
        if (oldLayer == null || !(oldLayer instanceof OsmDataLayer))
            return;
        OsmDataLayer dataLayer = (OsmDataLayer) oldLayer;

        Iterator<Entry<DialogContext, TurnRestrictionEditor>> it = openDialogs.entrySet().iterator();
        while (it.hasNext()) {
            Entry<DialogContext, TurnRestrictionEditor> entry = it.next();
            if (entry.getKey().matchesLayer(dataLayer)) {
                TurnRestrictionEditor editor = entry.getValue();
                it.remove();
                editor.setVisible(false);
                editor.dispose();
            }
        }
    }

    @Override
    public void layerAdded(LayerAddEvent e) {
        // irrelevant in this context
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        // irrelevant in this context
    }
}
