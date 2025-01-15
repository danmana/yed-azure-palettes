package com.justinderby.yed;

import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.svggen.SVGGeneratorContext;
import org.apache.batik.svggen.SVGGraphics2D;
import org.apache.batik.swing.svg.GVTTreeBuilderAdapter;
import org.apache.batik.swing.svg.GVTTreeBuilderEvent;
import org.apache.batik.swing.svg.JSVGComponent;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.svg.SVGDocument;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Icon {

    private static final int MAX_HEIGHT_WIDTH = 60;

    private final SVGDocument document;
    private final String realName;
    private final String name;
    private Rectangle2D bounds;

    public Icon(SVGDocument document, String realName, String name) {
        this.document = Objects.requireNonNull(document);
        this.realName = realName;
        this.name = name;
    }

    public SVGGraphics2D getGraphics() {
        SVGGeneratorContext ctx = SVGGeneratorContext.createDefault(this.document);
        // We don't need a comment
        ctx.setComment(null);
        return new SVGGraphics2D(ctx, false);
    }

    public String getRealName() {
        return this.realName;
    }

    public String getName() {
        return this.name;
    }

    private Rectangle2D getBounds() {
        if (this.bounds != null) {
            return this.bounds;
        }

        // First try the original JSVGComponent approach with a timeout
        try {
            return getBoundsWithJSVGComponent();
        } catch (Exception e) {
            System.err.println("JSVGComponent approach failed for " + getRealName() + ", falling back to sanitization: " + e.getMessage());
            // Fall back to sanitization if the original approach fails
            return getBoundsWithSanitization();
        }
    }

    private Rectangle2D getBoundsWithJSVGComponent() {
        final CompletableFuture<Rectangle2D> bounds = new CompletableFuture<>();
        final JSVGComponent component = new JSVGComponent();
        
        try {
            component.addGVTTreeBuilderListener(new GVTTreeBuilderAdapter() {
                @Override
                public void gvtBuildCompleted(GVTTreeBuilderEvent event) {
                    System.out.println("Build completed for " + getRealName());
                    bounds.complete(event.getGVTRoot().getBounds());
                }
                
                @Override
                public void gvtBuildFailed(GVTTreeBuilderEvent event) {
                    String message = String.format("Build failed for %s", getRealName());
                    System.err.println(message);
                    bounds.completeExceptionally(new RuntimeException(message));
                }
                
                @Override
                public void gvtBuildCancelled(GVTTreeBuilderEvent event) {
                    String message = String.format("Build cancelled for %s", getRealName());
                    System.err.println(message);
                    bounds.completeExceptionally(new RuntimeException(message));
                }
            });

            component.setSize(new Dimension(MAX_HEIGHT_WIDTH, MAX_HEIGHT_WIDTH));
            component.setSVGDocument(this.document);

            try {
                return bounds.get(2, TimeUnit.SECONDS);  // Timeout after 2 seconds
            } catch (TimeoutException e) {
                throw new RuntimeException("Timed out getting bounds", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
                throw new RuntimeException("Interrupted while getting bounds", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Error getting bounds", e.getCause());
            }
        } finally {
            component.dispose();
        }
    }

    private Rectangle2D getBoundsWithSanitization() {
        // Get root element
        var root = this.document.getDocumentElement();
        
        // Try to get dimensions from viewBox first
        String viewBox = root.getAttribute("viewBox");
        if (viewBox != null && !viewBox.isEmpty()) {
            String[] parts = viewBox.split("\\s+");
            if (parts.length == 4) {
                try {
                    float width = Float.parseFloat(parts[2]);
                    float height = Float.parseFloat(parts[3]);
                    return this.bounds = new Rectangle2D.Float(0, 0, width, height);
                } catch (NumberFormatException e) {
                    // Fall through to width/height attributes
                }
            }
        }
        
        // Use width/height attributes as fallback
        String width = root.getAttribute("width").replaceAll("[^0-9.]", "");
        String height = root.getAttribute("height").replaceAll("[^0-9.]", "");
        
        if (width.isEmpty() || height.isEmpty()) {
            return this.bounds = new Rectangle2D.Float(0, 0, MAX_HEIGHT_WIDTH, MAX_HEIGHT_WIDTH);
        }
        
        try {
            return this.bounds = new Rectangle2D.Float(0, 0, 
                Float.parseFloat(width), 
                Float.parseFloat(height));
        } catch (NumberFormatException e) {
            // If all else fails, use MAX_HEIGHT_WIDTH as default
            return this.bounds = new Rectangle2D.Float(0, 0, MAX_HEIGHT_WIDTH, MAX_HEIGHT_WIDTH);
        }
    }
    public Dimension getDimension(int maxHeightWidth) {        
        Rectangle2D bounds = getBounds();        
        double scaleFactor = Math.min(
                maxHeightWidth / bounds.getWidth(),
                maxHeightWidth / bounds.getHeight());        
        return new Dimension(
                (int)Math.min(Math.ceil(bounds.getWidth() * scaleFactor), maxHeightWidth),
                (int)Math.min(Math.ceil(bounds.getHeight() * scaleFactor), maxHeightWidth));
    }

    public int getHeight() {
        return (int)getDimension(MAX_HEIGHT_WIDTH).getHeight();
    }

    public int getWidth() {
        return (int)getDimension(MAX_HEIGHT_WIDTH).getWidth();
    }

    public String toXMLString() throws IOException {
        try (StringWriter writer = new StringWriter()) {
            this.getGraphics().stream(document.getRootElement(), writer, true, true);
            return writer.toString();
        }
    }

    private static String getReadableName(String name) {
        // Remove extension
        if (name.contains(".")) {
            name = name.substring(0, name.lastIndexOf("."));
        }
        return name
                .replace("light-bg", "")
                .replace("dark-bg", "")
                .replace('-', ' ')
                .replace('_', ' ')
                .trim();
    }

    private static SVGDocument sanitizeSVG(SVGDocument document) {
        // Find and remove problematic filter elements
        cleanupElements(document.getRootElement(), "filter");
        
        // Remove references to the filters in style attributes
        cleanupFilterReferences(document.getRootElement());
        
        return document;
    }

    private static void cleanupElements(Element element, String tagToRemove) {
        // Remove direct child elements of the specified tag
        NodeList children = element.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element childElement = (Element) child;
                if (childElement.getTagName().contains(tagToRemove)) {
                    element.removeChild(child);
                } else {
                    cleanupElements(childElement, tagToRemove);
                }
            }
        }
    }

    private static void cleanupFilterReferences(Element element) {
        // Remove filter references from style attributes
        String style = element.getAttribute("style");
        if (style != null && !style.isEmpty()) {
            style = style.replaceAll("filter:url\\([^)]+\\);?", "")
                        .replaceAll(";;", ";")
                        .trim();
            if (style.isEmpty()) {
                element.removeAttribute("style");
            } else {
                element.setAttribute("style", style);
            }
        }
        
        // Remove filter attributes
        if (element.hasAttribute("filter")) {
            element.removeAttribute("filter");
        }
        
        // Process child elements
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                cleanupFilterReferences((Element) child);
            }
        }
    }

    public static Icon fromFile(File svg) {
        final String parser = XMLResourceDescriptor.getXMLParserClassName();
        final SAXSVGDocumentFactory factory = new SAXSVGDocumentFactory(parser);
        try(var fis = new FileInputStream(svg)) {
            SVGDocument document = factory.createSVGDocument(null, fis);
            document = sanitizeSVG(document); // Sanitize before creating the Icon
            return new Icon(document, svg.getName(), getReadableName(svg.getName()));
        } catch (IOException e) {
            throw new RuntimeException("Error parsing " + svg.getAbsolutePath(), e);
        }
    }
}
