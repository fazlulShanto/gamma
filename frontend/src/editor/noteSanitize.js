// Notes may come from collaborators, imports or AI tools. Parse their HTML,
// then sanitize it before KaTeX adds its own trusted SVG/MathML and styles.
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";

const calloutClasses = ["className", "callout", "calloutFold", /^callout-(note|tip|warning|danger|important|quote)$/];
const schema = {
  ...defaultSchema,
  tagNames: [...defaultSchema.tagNames, "mark", "u"],
  strip: [...defaultSchema.strip, "style", "iframe", "object", "embed", "form", "textarea", "select", "button", "svg", "math"],
  attributes: {
    ...defaultSchema.attributes,
    blockquote: [...defaultSchema.attributes.blockquote, calloutClasses],
    details: [calloutClasses],
    p: [["className", "calloutTitle"]],
    summary: [...defaultSchema.attributes.summary, ["className", "calloutTitle"]],
    span: ["style"],
    code: [
      ["className", /^language-./, "math-inline", "math-display"],
      ["dataDiagramKind", "svg", "mermaid"],
      ["dataDiagramPending", "true", "false"],
      ["dataDiagramWidth", /^\d+$/],
    ],
  },
  protocols: {
    ...defaultSchema.protocols,
    href: [...defaultSchema.protocols.href, "blockref", "blockembed", "unlinked", "mention"],
  },
};

// The editor writes color/tint spans. Keep those without permitting arbitrary
// CSS (page overlays, external resource loads, escapes or custom properties).
const COLOR = /^(?:#[\da-f]{3,8}|[a-z]+|(?:rgb|hsl)a?\([\d\s.,%+\-/a-z]*\))$/i;
function colorStyle(value) {
  return String(value || "").split(";").flatMap((declaration) => {
    const colon = declaration.indexOf(":");
    const name = declaration.slice(0, colon).trim().toLowerCase();
    const color = declaration.slice(colon + 1).trim();
    if (colon < 0 || !["color", "background", "background-color"].includes(name) || !COLOR.test(color)) return [];
    return [`${name === "background" ? "background-color" : name}:${color}`];
  }).join(";");
}

export function rehypeNoteSanitize() {
  const sanitize = rehypeSanitize(schema);
  return (tree) => {
    function visit(node) {
      if (node.properties?.style !== undefined) {
        const style = node.tagName === "span" ? colorStyle(node.properties.style) : "";
        if (style) node.properties.style = style;
        else delete node.properties.style;
      }
      node.children?.forEach(visit);
    }
    visit(tree);
    return sanitize(tree);
  };
}
