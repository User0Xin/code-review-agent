import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import "./markdown-content.css";

const blockedElements = ["img"];

export function MarkdownContent({ children }: { children: string }) {
  return (
    <div className="markdown-content">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        disallowedElements={blockedElements}
        components={{
          table: ({ children: cells }) => (
            <div className="markdown-table-scroll" role="region" aria-label="表格" tabIndex={0}>
              <table>{cells}</table>
            </div>
          ),
          a: ({ children: label, href, title }) => (
            <a href={href} title={title} target="_blank" rel="noopener noreferrer">
              {label}
            </a>
          ),
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}
