package com.simpligility.maven.util;

import module java.base;
import module java.xml;

/// DOM helpers used by the analyzer's POM parsing.
///
/// [#directElement] and [#directText] look only at *direct* child elements of the given
/// parent — Maven's POM model is order-sensitive and tag names like `dependency`
/// reappear at multiple nesting levels, so the deep-search [Element#getElementsByTagName]
/// is the wrong default.
public final class Dom {

    private Dom() {}

    public static String directText(Element parent, String tagName) {
        Element el = directElement(parent, tagName);
        return el != null ? el.getTextContent().trim() : null;
    }

    public static Element directElement(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el) {
                String name = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
                if (tagName.equals(name)) return el;
            }
        }
        return null;
    }

    /// Iterates the [Element]s in a [NodeList], skipping non-element nodes (text, comments).
    public static Iterable<Element> elements(NodeList list) {
        return () -> new Iterator<>() {
            int i = 0;

            @Override
            public boolean hasNext() {
                while (i < list.getLength() && !(list.item(i) instanceof Element)) i++;
                return i < list.getLength();
            }

            @Override
            public Element next() {
                if (!hasNext()) throw new NoSuchElementException();
                return (Element) list.item(i++);
            }
        };
    }
}
