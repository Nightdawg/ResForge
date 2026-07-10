package resforge.gui;

import resforge.res.ResContainer;

/**
 * The narrow set of operations {@link LayerEditors} needs from the hosting
 * {@link ResForgeFrame}. Keeping it small (document access, the tested
 * file/edit actions, and a little player/timer lifecycle) lets the per-layer
 * editor panels live in their own class without reaching into the frame's
 * internals (table model, undo stacks, dialogs, threading), which all stay in
 * the frame.
 */
interface EditorHost {
    /** The document currently being edited (never null while a layer is shown). */
    ResContainer res();

    /** Replaces a layer's entire payload (header/whole-payload edits). */
    void setLayerPayload(int idx, byte[] payload);

    /** Routes a content edit through the tested {@code Replacer} by absolute index.
     *  Returns true only when the document mutation was committed. */
    boolean applyBytes(int idx, byte[] bytes);

    /** Opens a file chooser and replaces the layer's media/content from disk. */
    void replaceFromFile(int idx, String layerName);

    /** Opens a save chooser and exports the layer's editable content to disk. */
    void exportLayer(int idx);

    /** Replaces a tex layer's alpha mask from a chosen image file. */
    void replaceTexMask(int idx);

    /** Exports a tex layer's alpha mask to a chosen file. */
    void exportTexMask(int idx);

    /** Shows a transient status message. */
    void setStatus(String s);

    /** Shows an error message to the user. */
    void error(String msg);

    /** Registers the audio player for the current selection (for lifecycle/cleanup). */
    void setCurrentPlayer(AudioPlayerPanel player);

    /** Registers the animation timer for the current selection (for lifecycle/cleanup). */
    void setAnimTimer(javax.swing.Timer timer);

    /** Exports the open resource's 3D model to glTF (toolbar/model-panel action). */
    void exportGltf();

    /** Rebuilds the open resource's geometry from an edited glTF. */
    void rebuildGltf();
}
