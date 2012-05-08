package nodebox.client;

import edu.umd.cs.piccolo.PNode;
import edu.umd.cs.piccolo.event.PBasicInputEventHandler;
import edu.umd.cs.piccolo.event.PInputEvent;
import edu.umd.cs.piccolo.util.PPaintContext;
import nodebox.node.*;
import nodebox.ui.SwingUtils;
import nodebox.ui.Theme;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;

public class NodeView extends PNode implements Selectable, PropertyChangeListener {

    public static final int NODE_FULL_SIZE = 70;
    public static final int NODE_IMAGE_SIZE = 50;
    public static final int TEXT_HEIGHT = 14;
    public static final int NODE_OUTPUT_DRAG_ZONE = 20;
    public static final Rectangle OUTPUT_BOUNDS = new Rectangle(NODE_FULL_SIZE - NODE_OUTPUT_DRAG_ZONE, 0, NODE_OUTPUT_DRAG_ZONE, NODE_FULL_SIZE);
    public static final int NODE_PORT_HEIGHT = 7;
    private static final int NODE_PORT_MARGIN = 1;
    public static final int GRID_SIZE = 20;

    private static BufferedImage nodeMask, nodeGlow, nodeConnectionGlow, nodeInPort, nodeOutPort, nodeGeneric, nodeError, nodeRendered, nodeCodeChanged, nodeRim;

    static {
        try {
            nodeMask = ImageIO.read(new File("res/node-mask.png"));
            nodeGlow = ImageIO.read(new File("res/node-glow.png"));
            nodeConnectionGlow = ImageIO.read(new File("res/node-connection-glow.png"));
            nodeInPort = ImageIO.read(new File("res/node-in-port.png"));
            nodeOutPort = ImageIO.read(new File("res/node-out-port.png"));
            nodeGeneric = ImageIO.read(new File("res/node-generic.png"));
            nodeError = ImageIO.read(new File("res/node-error.png"));
            nodeRendered = ImageIO.read(new File("res/node-rendered.png"));
            nodeCodeChanged = ImageIO.read(new File("res/node-codechanged.png"));
            nodeRim = ImageIO.read(new File("res/node-rim.png"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private final NetworkView networkView;
    private final String nodeName;
    private BufferedImage fullIcon;
    private Border border;

    private boolean selected;
    private transient boolean codeChanged;
    private transient double fakeX, fakeY;

    public NodeView(NetworkView networkView, String nodeName) {
        this.networkView = networkView;
        this.nodeName = nodeName;
        this.selected = false;
        this.codeChanged = false;
        setTransparency(1.0F);
        addInputEventListener(new NodeHandler());
        setOffset(getNode().getPosition().toPoint2D());
        setBounds(0, 0, NODE_FULL_SIZE, NODE_FULL_SIZE + TEXT_HEIGHT);
        addPropertyChangeListener(PROPERTY_TRANSFORM, this);
        addPropertyChangeListener(PROPERTY_BOUNDS, this);
        addInputEventListener(new PopupHandler());
        updateIcon();
    }

    public NetworkView getNetworkView() {
        return networkView;
    }

    public NodeBoxDocument getDocument() {
        return networkView.getDocument();
    }

    public Node getNode() {
        return networkView.getActiveNetwork().getChild(nodeName);
    }

    public String getNodeName() {
        return nodeName;
    }

    /**
     * Tries to find an image representation for the node.
     * The image should be located near the library, and have the same name as the library.
     * <p/>
     * If this node has no image, the prototype is searched to find its image. If no image could be found,
     * a generic image is returned.
     *
     * @param node the node
     * @param nodeRepository the list of nodes to look for the icon
     * @return an Image object.
     */
    public static BufferedImage getImageForNode(Node node, NodeRepository nodeRepository) {
        for (NodeLibrary library : nodeRepository.getLibraries()) {
            BufferedImage img = findNodeImage(library, node);
            if (img != null) {
                return img;
            }
        }
        if (node.getPrototype() != null) {
            return getImageForNode(node.getPrototype(), nodeRepository);
        } else {
            return nodeGeneric;
        }
    }

    public static BufferedImage findNodeImage(NodeLibrary library, Node node) {
        if (node == null || node.getImage() == null || node.getImage().isEmpty()) return null;
        if (!library.getRoot().hasChild(node)) return null;
        File libraryFile = library.getFile();
        if (libraryFile != null) {
            File libraryDirectory = libraryFile.getParentFile();
            if (libraryDirectory != null) {
                File nodeImageFile = new File(libraryDirectory, node.getImage());
                if (nodeImageFile.exists()) {
                    try {
                        return ImageIO.read(nodeImageFile);
                    } catch (IOException ignored) {
                        // Pass through
                    }
                }
            }
        }
        return null;
    }

    /**
     * Calculate the vertical offset for the port. This value starts from the full node size.
     *
     * @param node The node the port is on.
     * @param port The port. The index of the port is used to calculate the offset.
     * @return the vertical offset
     */
    public static int getVerticalOffsetForPort(Node node, Port port) {
        checkNotNull(node);
        checkNotNull(port);
        int portIndex = node.getInputs().indexOf(port);
        int portCount = node.getInputs().size();
        int totalPortsHeight = (NODE_PORT_HEIGHT + NODE_PORT_MARGIN) * (portCount - 1) + NODE_PORT_HEIGHT;
        int offsetPerPort = NODE_PORT_HEIGHT + NODE_PORT_MARGIN;
        int portStartY = (NODE_FULL_SIZE - totalPortsHeight) / 2 - 1;
        return portStartY + portIndex * offsetPerPort;
    }

    /**
     * Create an icon with the node's image and the rounded embellishments.
     *
     *
     * @param node      The node.
     * @param nodeRepository The node repository to look for the prototype.
     *@param drawPorts If true, draw the ports as well.  @return an Image object.
     * @return The full node image
     */
    public BufferedImage getFullImageForNode(Node node, NodeRepository nodeRepository, boolean drawPorts) {
        Image icon = getImageForNode(node, nodeRepository);
        // Create the icon.
        // We include only the parts that are not changed by state.
        // This means leaving off the error and rendered image.
        // Also, we draw the rim at the very end, above the error and rendered,
        // so we can't draw it here yet.
        BufferedImage fullIcon = new BufferedImage(NODE_FULL_SIZE, NODE_FULL_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D fg = fullIcon.createGraphics();
        if (drawPorts) {
            // Count the input ports and draw them.
            java.util.List<Port> inputs = node.getInputs();
            for (Port p : inputs) {
                int portY = getVerticalOffsetForPort(node, p);
                fg.drawImage(nodeInPort, 0, portY, null);
            }
            fg.drawImage(nodeOutPort, 0, 0, null);
        }
        // Draw the other layers.
        fg.drawImage(nodeMask, 0, 0, null);
        fg.setComposite(AlphaComposite.SrcIn);
        fg.drawImage(icon, 10, 10, NODE_IMAGE_SIZE, NODE_IMAGE_SIZE, null);
        fg.setComposite(AlphaComposite.SrcOver);
        //fg.drawImage(nodeReflection, 0, 0, null);
        fg.dispose();
        return fullIcon;
    }

    public void updateIcon() {
        fullIcon = getFullImageForNode(getNode(), getDocument().getNodeRepository(), true);
    }

    public void propertyChange(PropertyChangeEvent evt) {
        if (evt.getPropertyName().equals(PROPERTY_TRANSFORM)) {
            getDocument().setNodePosition(getNode(), new nodebox.graphics.Point(super.getOffset()));
        }
    }

    public Border getBorder() {
        return border;
    }

    public void setBorder(Border border) {
        this.border = border;
    }

    protected void paint(PPaintContext ctx) {
        Graphics2D g = ctx.getGraphics();
        Shape clip = g.getClip();
        g.clip(getBounds());

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);

        // Draw the selection/connection border
        if (selected && networkView.getConnectionTarget() != this)
            g.drawImage(nodeGlow, 0, 0, null);
        if (networkView.getConnectionTarget() == this)
            g.drawImage(nodeConnectionGlow, 0, 0, null);
        g.drawImage(fullIcon, 0, 0, null);
        if (codeChanged)
            g.drawImage(nodeCodeChanged, 0, 0, null);

        // TODO Add support for node errors.
        //if (node.hasError())
        //    g.drawImage(nodeError, 0, 0, null);

        if (networkView.getActiveNetwork().getRenderedChildName().equals(getNodeName()))
            g.drawImage(nodeRendered, 0, 0, null);

        g.drawImage(nodeRim, 0, 0, null);

        // Draw the node name.
        g.setFont(Theme.SMALL_BOLD_FONT);
        g.setColor(Theme.NETWORK_NODE_NAME_COLOR);
        int textWidth = g.getFontMetrics().stringWidth(getNodeName());
        int x = (int) ((NODE_FULL_SIZE - textWidth) / 2f);
        SwingUtils.drawShadowText(g, getNodeName(), x, NODE_FULL_SIZE + 5, Theme.NETWORK_NODE_NAME_SHADOW_COLOR, -1);

        // Reset the clipping.
        g.setClip(clip);
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean s) {
        if (selected == s) return;
        selected = s;
        repaint();
    }

    public boolean hasCodeChanged() {
        return codeChanged;
    }

    public void setCodeChanged(boolean changed) {
        if (codeChanged == changed) return;
        codeChanged = changed;
        repaint();
    }

    private void doRename() {
        String s = JOptionPane.showInputDialog(networkView, "New name:", getNodeName());
        if (s == null || s.length() == 0)
            return;
        try {
            getDocument().setNodeName(getNode(), s);
        } catch (InvalidNameException ex) {
            JOptionPane.showMessageDialog(networkView, "The given name is not valid.\n" + ex.getMessage(), Application.NAME, JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public Point2D getOffset() {
        return new Point2D.Double(fakeX, fakeY);
    }

    @Override
    public void setOffset(Point2D pt) {
        setOffset(pt.getX(), pt.getY());
    }

    @Override
    public void setOffset(double x, double y) {
        fakeX = x;
        fakeY = y;
        super.setOffset(snap(fakeX), snap(fakeY));
    }

    private static double snap(double value) {
        value = Math.round(value);
        return Math.round(value / GRID_SIZE) * GRID_SIZE;
    }

    private class NodeHandler extends PBasicInputEventHandler {

        protected Point2D dragPoint;
        private boolean isDragging;

        public void mouseClicked(PInputEvent e) {
            if (e.getClickCount() == 1) {
                e.getInputManager().setKeyboardFocus(this);
                networkView.singleSelect(NodeView.this);
            } else if (e.getClickCount() == 2 && e.isLeftMouseButton()) {
                Point2D pt = NodeView.this.getOffset();
                double y = e.getPosition().getY() - pt.getY();
                // Check if we're clicking on the label, which is below the node.
                // We give the user some space below to compensate for the glow.
                if (y > NODE_FULL_SIZE - 4) {
                    doRename();
                } else {
                    getDocument().setRenderedNode(getNode());
                }
            }
            e.setHandled(true);
        }

        public void mousePressed(PInputEvent e) {
            if (isPanningEvent(e)) return;
            if (e.getButton() == MouseEvent.BUTTON1) {
                Point2D pt = NodeView.this.getOffset();
                double x = e.getPosition().getX() - pt.getX();
                double y = e.getPosition().getY() - pt.getY();

                // Find the area where the mouse is pressed
                // Possible areas are the output connector and the node itself.
                if (OUTPUT_BOUNDS.contains(x, y)) {
                    isDragging = false;
                    networkView.startConnection(NodeView.this);
                } else {
                    isDragging = true;
                    dragPoint = e.getPosition();
                    // Make sure that this node is also selected.
                    if (!isSelected()) {
                        // If other nodes are selected, deselect them so they
                        // don't get dragged along.
                        networkView.singleSelect(NodeView.this);
                    }
                }
            }
        }

        public void mouseEntered(PInputEvent e) {
            if (networkView.isConnecting() && networkView.getConnectionSource() != NodeView.this) {
                networkView.setTemporaryConnectionTarget(NodeView.this);
            }
        }

        public void mouseExited(PInputEvent e) {
            if (networkView.isConnecting()) {
                networkView.setTemporaryConnectionTarget(null);
            }
        }

        public void mouseDragged(PInputEvent e) {
            if (isPanningEvent(e)) return;
            if (isDragging) {
                checkNotNull(dragPoint, "dragPoint cannot be null.");
                Point2D pt = e.getPosition();
                double dx = pt.getX() - dragPoint.getX();
                double dy = pt.getY() - dragPoint.getY();
                getNetworkView().dragSelection(dx, dy);
                dragPoint = pt;
            } else if (networkView.isConnecting()) {
                Point2D p = e.getPosition();
                networkView.dragConnectionPoint(p);
            }
            e.setHandled(true);
        }

        public void mouseReleased(PInputEvent event) {
            if (networkView.isConnecting()) {
                // Check if both source and target are set.
                if (networkView.getConnectionSource() != null && networkView.getConnectionTarget() != null) {
                    Node outputNode = networkView.getConnectionSource().getNode();
                    Node inputNode = networkView.getConnectionTarget().getNode();
                    // Look for compatible ports.
                    java.util.List<Port> compatiblePorts = inputNode.getInputs();
                    if (compatiblePorts.isEmpty()) {
                        // There are no compatible parameters.
                    } else if (compatiblePorts.size() == 1) {
                        // Only one possible connection, make it now.
                        Port inputPort = compatiblePorts.get(0);
                        try {
                            getDocument().connect(outputNode, inputNode, inputPort);
                        } catch (ConnectionError e) {
                            JOptionPane.showMessageDialog(networkView, e.getMessage(), "Connection error", JOptionPane.ERROR_MESSAGE);
                        }
                    } else {
                        JPopupMenu menu = new JPopupMenu("Select input");
                        for (Port p : compatiblePorts) {
                            Action a = new SelectCompatiblePortAction(outputNode, inputNode, p);
                            menu.add(a);
                        }
                        Point pt = getNetworkView().getMousePosition();
                        menu.show(getNetworkView(), pt.x, pt.y);
                    }
                }
                networkView.endConnection();
            }
        }

        private boolean isPanningEvent(PInputEvent event) {
            return networkView.isPanning();
        }

    }

    class SelectCompatiblePortAction extends AbstractAction {

        private final Node outputNode;
        private final Node inputNode;
        private final Port inputPort;

        SelectCompatiblePortAction(Node outputNode, Node inputNode, Port inputPort) {
            super(inputPort.getLabel());
            this.outputNode = outputNode;
            this.inputNode = inputNode;
            this.inputPort = inputPort;
        }

        public void actionPerformed(ActionEvent e) {
            try {
                getDocument().connect(outputNode, inputNode, inputPort);
            } catch (ConnectionError ce) {
                JOptionPane.showMessageDialog(networkView, ce.getMessage(), "Connection error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private class PopupHandler extends PBasicInputEventHandler {
        public void processEvent(PInputEvent e, int i) {
            if (!e.isPopupTrigger()) return;
            JPopupMenu menu = new JPopupMenu();
            menu.add(new SetRenderedAction());
            menu.add(new RenameAction());
            menu.add(new DeleteAction());
            menu.add(new GoInAction());
            Point2D p = e.getCanvasPosition();
            menu.show(NodeView.this.networkView, (int) p.getX(), (int) p.getY());
            e.setHandled(true);
        }
    }

    private class SetRenderedAction extends AbstractAction {
        private SetRenderedAction() {
            super("Set Rendered");
        }

        public void actionPerformed(ActionEvent e) {
            getDocument().setRenderedNode(getNode());
            networkView.repaint();
        }
    }

    private class RenameAction extends AbstractAction {
        private RenameAction() {
            super("Rename");
        }

        public void actionPerformed(ActionEvent e) {
            doRename();
        }
    }


    private class DeleteAction extends AbstractAction {

        public DeleteAction() {
            super("Delete");
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0));
        }

        public void actionPerformed(ActionEvent e) {
            getDocument().removeNode(getNode());
        }
    }

    private class GoInAction extends AbstractAction {
        private GoInAction() {
            super("Edit Children");
            putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        }

        public void actionPerformed(ActionEvent e) {
            String childPath = Node.path(getDocument().getActiveNetworkPath(), getNodeName());
            getDocument().setActiveNetwork(childPath);
        }
    }

}


