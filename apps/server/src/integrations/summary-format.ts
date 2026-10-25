import { fromMarkdown } from "mdast-util-from-markdown";

const sectionTitles = ["适用范围", "支持证据", "建议", "局限"] as const;

interface ContentNode {
  type: string;
  value?: string;
  children?: ContentNode[];
}

function hasContent(node: ContentNode): boolean {
  if (
    node.type === "definition" ||
    node.type === "html" ||
    node.type === "thematicBreak" ||
    node.type === "image" ||
    node.type === "imageReference"
  ) {
    return false;
  }
  if (node.value?.trim()) {
    return true;
  }
  return node.children?.some(hasContent) ?? false;
}

/** Validate the source Markdown without repairing or rewriting generated content. */
export function summaryFormatError(body: string): string | undefined {
  const tree = fromMarkdown(body);
  const headings = tree.children.filter((node) => node.type === "heading");
  if (headings.length !== sectionTitles.length || tree.children[0] !== headings[0]) {
    return "正文必须直接从三级标题开始，并依次包含且仅包含适用范围、支持证据、建议、局限四个独立分节。";
  }

  const lines = body.split(/\r\n|\n|\r/);
  for (const [index, title] of sectionTitles.entries()) {
    const heading = headings[index];
    if (
      !heading ||
      heading.depth !== 3 ||
      heading.children.length !== 1 ||
      heading.children[0]?.type !== "text" ||
      heading.children[0].value !== title
    ) {
      return `第 ${index + 1} 节必须使用独立的三级标题“### ${title}”，不得缺失、重复或调整顺序。`;
    }

    const startLine = heading.position?.start.line;
    const endLine = heading.position?.end.line;
    if (
      !startLine ||
      !endLine ||
      (startLine > 1 && lines[startLine - 2]?.trim() !== "") ||
      lines[endLine]?.trim() !== ""
    ) {
      return `“### ${title}”与前后正文之间必须保留空行。`;
    }

    const start = tree.children.indexOf(heading) + 1;
    const nextHeading = headings[index + 1];
    const end = nextHeading ? tree.children.indexOf(nextHeading) : tree.children.length;
    if (!tree.children.slice(start, end).some(hasContent)) {
      return `“### ${title}”分节必须包含非空正文。`;
    }
  }
  return undefined;
}
