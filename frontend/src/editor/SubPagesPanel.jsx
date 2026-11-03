// "N pages inside": the open page's sub-pages (the pages whose parent_page
// is it), under its notes beside "Linked from", in library order. A row opens
// that page; "New page inside", when `onNew` is given, makes another. Nothing
// shows for a page with none: the page's menu makes the first. The collapsed
// state is a browser preference (subPagesCollapsed). App fetches the list
// (GET /pages/{id}/family's `sub_pages`: [{id, title, updated_at, pdf}],
// `pdf` true on one that carries a PDF, so a share view, which has no
// library listing, draws the right glyph too) and does the navigation and
// the creation.
import React from "react";
import { FileGlyph, PlusIcon } from "../shared/ui/Icons";
import { Collapse } from "../shared/ui/MotionWidgets";
import { t, tn } from "../shared/i18n/i18n.js";

export function SubPagesPanel({ pages, onOpen, onNew, collapsed, onCollapsedChange }) {
  if (!pages?.length) return null;
  return (
    <section className="subPagesPanel" data-motion-key="subpages">
      <button type="button" className="backlinksHead" aria-expanded={!collapsed}
        onClick={() => onCollapsedChange(!collapsed)}>
        <span className={`backlinksCaret turnCaret${collapsed ? "" : " open"}`} aria-hidden="true">▸</span>
        {tn("{n} page inside", "{n} pages inside", pages.length)}
      </button>
      <Collapse open={!collapsed}>
        {pages.map((page) => {
          const title = page.title || t("Untitled");
          return (
            <button key={page.id} type="button" className="backlinkPage subPageRow"
              title={t("Open the page \"{name}\"", { name: title })} onClick={() => onOpen(page.id)}>
              <FileGlyph isPdf={!!page.pdf} size={14} />
              <span className="backlinkPageTitle">{title}</span>
            </button>
          );
        })}
        {onNew ? (
          <button type="button" className="backlinkPage subPageRow subPageNew" onClick={() => onNew()}>
            <PlusIcon size={14} />
            <span className="backlinkPageTitle">{t("New page inside")}</span>
          </button>
        ) : null}
      </Collapse>
    </section>
  );
}
