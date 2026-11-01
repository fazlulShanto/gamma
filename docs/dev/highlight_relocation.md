# Highlights after a PDF is replaced

A page's PDF can be replaced by another file: a paper compiled again from
its TeX, or the published version in place of the arXiv one. The
replacement comes through `PUT /pages/{id}/attachment` (the Document
menu's **Replace the PDF…**, a build script, [api.md](api.md) "Pages"). A
Zotero link also replaces it with the file Zotero's item holds
([zotero_sync.md](zotero_sync.md#pdfs)).

A highlight's place is a page and rectangles in that page's frame
(`pdf_position`, [api.md](api.md) "The highlight shape"). That place holds
for the file it was made on. In the new file every line has moved, but the
text stays. So after a replacement each highlight is found again in the
new file by its passage and moved there:

- Found exactly, it is drawn solid, as it was.
- Found changed a little (a renumbered citation, a reworded phrase), it is
  placed when it reads alike enough. It is drawn dashed and stays pending
  until the person compares it on its review card and accepts, moves or
  discards it.
- Found nowhere, or only in places too unlike it, it is marked `lost` and
  drawn hollow where it was. It keeps the place most like it as its
  closest match when that reads at least half alike.

A lost highlight stays so until the person acts or a later run finds it.
The person may move it, take that match on its review card (and accept
that place), or discard it.

Every run matches a highlight from its reference, the last place someone
confirmed. So a dashed one still pending at the next replacement is matched
from the file it was made on, not from the guess
([The reference](#the-reference)).

Code:

- `gamma/highlight_relocate.py`: the search and the write.
- `gamma/highlights.py`: the position's flags, `prev` and `closest`
  (`previous_place`, `valid_prev`, `valid_closest`, `from_scaled`).
- `pdf_text.GlyphPages`: the glyphs, a page's frame and the pictures the
  PDF itself draws.
- `routers/pages.py`: `replace_pdf` over `replace_attachment` (which the
  Zotero link calls too), `_start_relocation`, `POST
  /api/jobs/relocate-highlights`.
- `blocks_store.attachment_file`: the stored file a page shows.

In the web app:

- `pdf/PdfViewer.jsx`: the dashed and hollow looks, the closest match's
  outline, the tip's move button.
- `pdf/HighlightReview.jsx` with `pdf/highlightReview.js`: the review
  card, its queue and its walk, its steps and whether each is in effect.
  Also the one it goes on to after a decision, the note it shows, and
  Discard's step and its pure arithmetic.
- `shared/lib/wordDiff.js`: the card's word comparison.
- `shared/model/blockModel.js`: `placedToCheck`, `placeToReview`,
  `roughMatchPercent`, `acceptedPosition`, `closestPlace`,
  `closestPlacement`, `reviewUndone`, `blockPlace`, `insertBlockAt`.
- `editor/BlockTree.jsx`: the row's dashed and hollow dots, its move
  button.
- `app/App.jsx`: Accept, Use closest match, Discard and a step's undo. Also
  the stored position and note the card reads, the notes panel following
  the card, move mode, the Document menu's rows and the replacement's
  message.
- `app/prefDefs.js` and `settings/SettingsDialog.jsx`: the thresholds.
- `tasks/taskKinds.js` and `tasks/useAppJobs.js`: the task row, the
  summary when a run ends.

## The job

A `PUT /pages/{id}/attachment` that changes the stored file, on a page
with highlights placed on its PDF, does two things. In its own transaction
it writes which file each highlight's rectangles were measured on
(`pdf_position.doc_id`, the next section). After its commit it starts the
`relocate-highlights` job ([tasks.md](tasks.md)). The answer carries the
job as `job` beside the block. So the tab that replaced the file knows the
run at once and says what it found when it ends. The same file again, or
the same stored file from another address (only `source_url` changes),
writes no `doc_id` and starts no run.

The stored file a page shows is its attachment's `doc_id`
(`blocks_store.attachment_file`, `libraryUtils.attachmentFile` in the web
app). A page without one shows the upload its `source_url` serves, when
that is exactly `/api/uploads/<doc_id>.pdf`. The library's Duplicate gives
a copy only that address. A copy's highlights are therefore read from,
stamped with and placed in that stored file like the original's. The
copy's attachment `id` stays empty, as gating and lookups by attachment
expect.

A PDF shown from another URL with no `doc_id` is no stored file. (A
URL-opened PDF has one, the URL's hash, and the run fetches it as the
indexer does.) A replacement by such a URL starts no run and answers
`relocation_error` "the PDF is not stored on the server". The manual start
answers 400 with it. The Document menu's row is disabled, with a title that
says why.

The job's owner is the account behind the request: a session's, or a
write-scope integration token's. For a file a Zotero link brought, it is
the link's owner. A token's run shows in its account's Background tasks,
but the token itself reads no jobs (`/api/jobs` answers it 403). A share
link never reaches the endpoint.

A Zotero link sends a highlight to Zotero only once it is placed. It holds
one still measured on the old file (`pdf_position.doc_id`) as "being
placed in the new PDF". It holds a dashed one as "dashed in Gamma until
accepted" and a lost one as "not found in the new PDF". These count under
the link's waiting annotations
([zotero_sync.md](zotero_sync.md#annotations)). However a run ends,
written or not, the link is told once the job reads as ended
(`zotero_sync_pdfs.relocation_ended`). A PDF replaced here waits to go to
Zotero until its highlights are placed, and is decided then
([zotero_sync.md](zotero_sync.md#the-plan)).

The job's params are `{page_id, title, doc_id, min_match, exact_chars}`:
the file it places for and the thresholds it goes by
([The thresholds](#the-thresholds)). Its key is `<page id>:<doc_id>`, so
there is one run per page and file. A replacement first stops the same
account's run for the file it replaced, queued or running. That run's
result would be for a file the page no longer shows. The new run never
waits for it, since their keys differ. A run already past its last stop
finishes on its own, and its write after the replacement is refused
(below). Another member's run for the old file is left alone and fails at
its write the same way, writing nothing.

A run that cannot start leaves the replacement standing. It cannot start
when the account's background tasks are full (20 queued or running,
`jobs.start`'s 429). Nor can it while a run for the same page and file is
still stopping. The answer then carries the reason as `relocation_error`
instead of `job`. The log has `[relocate] <page>: the job did not start
(<reason>)`. The tab says "PDF replaced. Its highlights were not placed
(…): choose Find highlights in this PDF in the Document menu to place
them." The highlights keep the file their rectangles belong to, so a later
run reads them right.

The Document menu's **Find highlights in this PDF**, and a finished row's
Start again in Background tasks, start the run by hand (`POST
/api/jobs/relocate-highlights {page_id}`). It serves a replacement whose
run did not start, failed or was stopped. It also serves a page restored
from Recently deleted, and a PDF replaced before the job existed. It
needs a session account that edits the workspace. A token gets 403, as at
every job start. The answers:

- 200 and that run, while a run for the page's current file is going.
- 409 only while that run is still stopping.
- 429 when the account's jobs are full.
- 400 for a page without a PDF, with one not stored (above), or without
  highlights placed on it.
- 404 for no such page.

A manual run reads each highlight from its own file, as the replacement's
does.

The run (`relocate`) works from a snapshot of the page's placed highlights
(`page_highlights`, `highlights.is_highlight`). These are the highlights
and link regions with rectangles, whatever their depth in the notes. It
skips ink groups, text boxes, pictures, sheets and a position that names
only its page. The run opens the new file (`ai_context.pdf_path`: the
stored copy, or a URL-opened PDF fetched as the indexer fetches it). It
reads every page (phase `reading`, unit `pages`) and places each highlight
(phase `matching`, unit `highlights`). Then it closes the files and drops
all it read from them. Once the matching is done it reports so, and a stop
asked for until then lands there. It then marks itself unstoppable
(`stoppable=False`) and writes. A new file not stored on the server,
unreadable, or with no text layer on any page fails the run rather than
leaving every highlight lost.

**The write** (`_write`) is one compare-and-set, in one transaction under
the page's write lock. Two checks fail the run, and then nothing is
written:

- The page must still be live. One deleted or in Recently deleted fails
  with "the page was deleted before its highlights were placed — restore
  it and choose Find highlights in this PDF".
- The page must still carry the file the run placed for
  (`attachment_file`). Else the run fails with "the page's PDF changed
  again before the highlights were placed".

The op for a highlight deleted since the snapshot is dropped. So is one
whose `pdf_position` or `quote` changed (a person moved it meanwhile).
That highlight is left as it is and counted in `changed` instead of its
outcome. The rest go as `set` ops on the page, in batches of at most
`MAX_OPS`, client `relocate`, actor the owner. All are committed together,
then each batch's `after_commit` runs. They are ordinary ops, so they reach
the open tabs over the page socket and the viewer redraws.

The result is `{highlights, moved, kept, lost, approx, changed, min_match,
exact_chars}`. The five counts after `highlights` add up to it
([What a run writes](#what-a-run-writes)). The last two are the thresholds
the run went by.

## Which file a position was measured on

`pdf_position.doc_id` names the stored file a position's rectangles were
measured on, when that is no longer the page's PDF. It is a page `doc_id`,
the content hash. A position without it was measured on the page's current
PDF. That is what the viewer, the imports and a move by hand write, so none
of them know of the field. The page's previous file is not enough. Some
highlights' rectangles belong to a file before that one. A run that never
wrote (stopped, failed, refused, or one that could not start) leaves them.
So do a highlight left lost, and a wrong file put up and replaced again.

The replacement writes it in the transaction that changes the attachment.
It goes beside the marks on the blocks imported from the old file's
annotations, in batches of `MAX_OPS` the same way. Every placed highlight
of the page whose position names no file yet gets the outgoing file's id.
On a duplicated page that is the upload its address serves. A page that
showed its old file from another URL alone gets `unstored`
(`storage.UNSTORED`). Uploads are named by their content hash, so
`unstored` names none. A position that already names a file keeps it.

The same write drops a lost highlight's `closest`
([What a run writes](#what-a-run-writes)). That is a place in the outgoing
file. A run that fails or never starts would leave the card pointing into
a file the page no longer shows.

A backup's restore keeps `doc_id`, `lost`, `approx`, `match`, `prev` and
`closest` with the rest of the position. Every restored position passes
through `highlights.from_scaled`, which keeps `prev` and `closest` only in
their own form ([What a run writes](#what-a-run-writes)).

The run reads each highlight (its passage, context, anchor and picture)
from the file its reference names ([The reference](#the-reference)). That
is its own position's `doc_id`, or a pending one's `prev.doc_id`. It may
be the page's new file. Any other is looked for among the workspace's
uploads only (`find_upload_file`, never fetched). Each file is opened
once. A `doc_id` that is no file name names no file. A position without
`doc_id` is read from the page's new file, unless it is `lost`. A lost one
was not found in that file, so only its quote is looked for.

A file no longer stored logs `[relocate] <page>: the file <id> is no
longer stored; its highlights are placed by their quotes`. Its highlights
are placed that way, without context. A file is no longer stored when it
was purged before pending highlights held their files, or removed some
other way.

The stamp is a reference to the file, and so is a `prev`'s `doc_id`
(`storage.upload_refs`). So the file a pending highlight is matched from
stays stored while the highlight names it, through any number of later
replacements. It goes to the orphan bookkeeping once Accept, Use closest
match, a move, a Discard or a run that places it solid drops the name. A
file only stamps named, such as an in-between version's, goes after the
run that moves the rectangles off it. It is then purged 30 days after
nothing references it, or after the 1 to 30 days a sync's replacement
asked for (`keep_old_days`, [user_db.md](user_db.md) "Stored files"). The run
claims the files its written positions name (`upload_gc.claim` in
`_write`). That drops the shorter life of a file the run made a
highlight's reference. The file stays on the copy that holds it: a mirror
and an export through a share carry only the files the pages show.

A text highlight's stored quote and the text read under its rectangles
agree when, both folded, they read alike from `QUOTE_AGREES` (0.6,
difflib's ratio, `_agrees`). One that holds the other and is at least 0.6
of its length always agrees, and is told at once. A shorter one does not
count by being held. A rectangle now over a short line that happens to be
two words of the quote is not the quote. When they do not agree, the
rectangles do not belong to that file, and the quote is looked for without
context.

When they agree on the page's new file, the highlight is where it belongs
and is kept without a search. That is a position without `doc_id` (as in a
run by hand on an unchanged file), or one naming that very file. A
`doc_id` naming that file is dropped, and a `lost` mark is cleared with its
`closest`.

A position the run places or keeps carries no `doc_id`. One in a new place
is measured on the new file. One left where it was that named a file is
written once more without it. A lost one names its reference's file:
the old position with `lost: true`, or a pending one's `prev` turned back
into a position. So a later run still reads that highlight from the file it
was confirmed on. An area nothing placed names its file the same way, with
`approx: true` ([Areas](#areas)). The file a dashed placement's old
rectangles belong to is kept apart, in `prev`. The person compares against
it, and later runs match the highlight from it
([The reference](#the-reference)).

## Finding a passage

**The text.** `PageText` reads a page's glyphs as one string, each
character remembering its glyph. The glyphs come from `GlyphPages.page`:
each character with its box, and a boxless break after every line of
MuPDF's. Lines are joined with a space. A word hyphenated at a line's end
is joined whole ("entangle-" + "ment"). Every character is folded: dashes
to one hyphen, curly quotes straight, soft hyphens dropped, ligatures to
their letters (NFKC), case folded, whitespace collapsed.

A MuPDF line that is a number of one to five digits alone is left out with
its glyphs, in both files. They are margin line numbers (a review copy's
lineno) or a page number on a line of its own, which the other version
lacks. Every index counts the glyphs left. `normalize_quote` folds a stored
quote the same way, so the viewer's text of a selection (pdf.js's reading)
meets MuPDF's. A run keeps every page of the new file until its matching
ends. So a page is kept compactly: the chars as one string, the boxes as
one flat array.

**The passage.** In the file the highlight was measured on, its rectangles
go back to the glyphs' space through the export's mapper
(`pdf_export.viewer_rect_to_pdf`). The mapper takes the stored frame's
size, the page's view box from `GlyphPages.frame` and its rotation. The
glyphs whose centre the rectangles cover are the highlight's
(`pdf_text.covers`, the rule of the annotation import's `quote_under`). A
text highlight's needle is the text of those glyphs alone, read as a
selection reads (`_stretches`, `_joined`):

- Each run of covered glyphs is one stretch. A mark without a box does not
  break it.
- Two stretches the text already joins are one: a word hyphenated at a
  line's end, or a line that ends in a covered space.
- The stretches are joined with one space.
- A word hyphenated where the highlight skips a line break is joined whole.

So a caption or a margin note the file draws between two lines of the
highlight is not in the needle.

In a file other than the page's new one, the covered glyphs may run past
the highlight's stored quote. The quote's own copy on the page is then the
passage instead, as one stretch (`_on_quote`). Of the page's copies of the
folded quote, it is the one sharing the most text with the rectangles'
reading (the first on a tie). It takes over when that reading runs
past either of its ends. On the new file itself, what the rectangles read
as they are says whether they are over their quote
([Which file](#which-file-a-position-was-measured-on)).

Rectangles a little off the file's glyphs read the quote's neighbours too.
Their stored frame may not match their scale: a page size a few percent
off the one they were measured at. A sentence's line rectangles then sit a
line low or high. They read the end of the sentence before or the start of
the one after. A line's rectangle that runs on over the next word, or past
the column's edge into the next column, reads those words. Such a reading
is still alike enough to count as the quote (`_agrees`). Searched for as
it read, it would be found in the new file with those neighbours, which
would get rectangles of their own. If the neighbours changed (a renumbered
citation in the next sentence), the match would be rough. It would be drawn
dashed, with a rectangle over the next line. Taking the quote's own copy
(`_on_quote`) avoids that.

A reading within the copy stays as it is. The rectangles then say which of
the quote's words are the passage (a caption between two columns they
skip). The reading also stays when the page holds no copy of the quote
that it touches. That happens with a quote the viewer read otherwise than
MuPDF does, or with a margin line number in it. The context is the 40
characters (`CONTEXT_CHARS`) before the first stretch and after the last.

The highlight's **pieces** (`_pieces`, `Piece`) are its stretches as the
file read them. Stretches with nothing but a line's end between are one
piece. A new piece begins where the file read words the highlight does not
cover. Such words are a caption drawn between two columns, a footnote, or a
mark the rectangles miss. Each piece has its own 40 characters either side
and the number of words skipped before it. Most highlights have one piece.
A highlight from a column's foot to the next column's top has two, when
the page's content stream draws a caption between them.

An area's needle is the text from its first covered glyph to its last. Its
anchor is that text's line boxes (`rects_for_span`) as one box, in
fractions of the page. It is measured as the match in the new file is
measured, so an unchanged area is found where it was. The covered glyphs
alone would not do for an area over a table's column, which the file draws
row by row. Those glyphs never follow each other in the text, not even in
an identical file. The needle is the covered glyphs' text, and the anchor
their box, only when that span runs through much text the area does not
cover. That is a span longer than twice the covered glyphs plus ten, from
glyphs drawn far out of reading order.

Without the file, the stored `quote` is the needle, with no context and in
one piece. That covers a file gone or `unstored`, a lost position without
`doc_id`, and a quote that disagrees with its file.

**The candidates** (`find_passage`) are looked for on every page of the
new file. A needle of fewer than three characters (`MIN_NEEDLE`) is not
looked for at all.

- Every exact copy on word edges is a candidate, so "ion" in "motion" or
  "fig. 1" in "fig. 12" is not. The rule is lifted on a side where the
  context shows the old passage itself began or ended inside a word.
- The rough search (`_rough_on_page`) adds candidates for a needle of at
  least `exact_chars` characters (twelve by default,
  [The thresholds](#the-thresholds)), whatever its count of words.
- With no exact copy on any page, the passage across each page break is a
  candidate (`_across`). The needle needs at least `exact_chars` characters
  and two words.
- With no exact copy on any page, a passage the old file read in several
  pieces is looked for piece by piece (`_by_pieces`).

**The rough search** runs when there is no exact copy. It also runs when
there is a context and no exact copy stands in surroundings that read like
it from `EXACT_SURE` (0.9). So a passage reworded in place may beat a
verbatim copy elsewhere. On each page, a word votes for where the needle
would start when its core is one of the needle's words. A word's core is
the word without the punctuation at its edges. Around the six best-voted
starts (`MAX_SEEDS`, none within the slack of a better one), the needle is
aligned word by word (`_rows`, `_align`). It is a semi-global alignment.
The run may start and end anywhere in a window of the needle's length plus
the slack either side. On a tie the later start and the earliest end win,
so a dropped first or last word never pulls in the word beside it. No word
is frozen: a number, a citation, a symbol or a negation may change like
any other. The costs:

- A page word added costs one.
- A passage word dropped costs `DROP`, a hair more (one plus a millionth).
- A word read as another costs one minus how alike the two are
  (`_word_cost`).

The extra millionth makes a run that keeps the passage's last word beat one
as cheap that stops short of it. "in the cryostat." read in "in a new
cryostat." covers "cryostat.". A cut before "a new" would have cost the
same and read less alike.

How alike two words are is `_ends_alike`: twice the characters they share
at their start and, past it, at their end, over both lengths. For one
change inside a word that is difflib's ratio, at a twentieth of its time.
For two words that both have digits it is the mean of that and their
likeness with every digit read as 0. So "[27]," for "[25]," costs 0.1 and
"5" for "4" costs 0.5. A changed number at a passage's edge is therefore
read as the new number rather than dropped. A pair less than half alike
costs `DROP`, as a dropped word does. So a last word swapped for an unlike
one ("with this method." as "with this approach.") is still left out. The
costs cannot tell it from a dropped word before the next sentence's first.

**Across a page break** (`_across`), the needle's head is aligned against
one page's last words, its tail against the next page's first. Each window
is the needle's length and 20 more (`JOIN_SLACK`). Both parts are free to
start and end anywhere, so the footer, footnotes and running head between
them ("Phys. Rev. A 104, 033101 (2021)") cost nothing. The cut that costs
least in all wins, and each part must cover some words of its page. The
candidate is the part on the page with more of the passage's words (the
first on a tie). It is a `part`, whose quote becomes that part as the new
file spells it.

**Piece by piece** (`_by_pieces`), the search starts from the longest
piece (the first of the longest), wherever it is. That piece is found
exactly. When no exact copy leads anywhere, it is found roughly, from
`exact_chars` characters on. Each piece after it must begin within reach of
its neighbour, and each piece before it must end within reach (`_beside`).
The reach is the words the old file skipped between the two, plus 20
(`JOIN_SLACK`). So the caption still drawn between two columns costs
nothing.

A piece is found as a passage is (`_piece_hits`): exactly on word edges,
its own context lifting the rule on a side. Else, from `exact_chars`
characters on, the cheapest rough run within reach counts. A shorter piece
is found exactly or not at all. Among several hits of a piece within
reach, the cheapest wins (an exact copy first). Then one on the same page
wins, then the gap nearest the old one.

The next piece may lie on the next page when its neighbour is within that
reach of the page break. The piece before may lie on the previous page the
same way. The footer and running head between cost nothing. A chain
crosses one break at most. The highlight then goes to the page with more
of the passage's words (the first on a tie), covering the pieces there.
Its quote is theirs, as the new file spells it. The chains are candidates
beside the others. A chain's surroundings are the text before its first
piece and after its last.

Two heuristic shortcuts keep the rough search fast:

- An alignment gives up once it costs more than 0.4 per word of the
  needle, and at least one (`ROUGH_BOUND`, `_bound`).
- A page or a window is aligned only when the needle's words found there
  unchanged (up to punctuation) hold half its letters (`ROUGH_PRESENT`,
  `_holds`). `_holds_split` checks that for some cut at a page break.

A place past either is no candidate. Such a place reads, as a rule, far
less alike than the default threshold. A lowered threshold could have
taken it, though ([Limits](#limits-and-open-items)). Giving up early itself
changes no answer.

**The confidence.** A candidate's confidence is how alike the needle and
the text found there read, both folded. It is difflib's `SequenceMatcher`
ratio without its junk heuristic (`_similar`), 1.0 only for an exact copy.
Across a page break it is measured over both parts joined with a space. For
a passage found piece by piece, it compares the old pieces with the ones
found. Each side's pieces are joined with a space, the text between them
left out. The alignment's cost only finds the places. The confidence says
how alike each one is.

**The ranking.** Without a context, a candidate's score is its confidence.
With one, the score adds a fifth (`CONTEXT_WEIGHT`, 0.2) of the context
fit. That fit is the mean likeness (`_similar`) of each side the context
has to the 40 characters beside the candidate. Unrelated 40-character
windows score 0.30 at the median and 0.475 at the 99th percentile. Lightly
edited ones score 0.83 to 0.90. So a passage reworded in place, in
surroundings that read like the old ones, outranks a verbatim copy of it
in the conclusion. That holds as long as it reads more than about 0.86
like the old one. The copy scores 1.0 plus a fifth of some 0.3. The one in
place scores at most its confidence plus 0.2. Reworded further, the
highlight goes to the copy, solid ([Limits](#limits-and-open-items)).

On a tie the page nearest the highlight's old page wins, then the place
nearest the old one. Nearness compares two centres, each as fractions of
its page: the stored `boundingRect`'s and that of the candidate's glyph
boxes on the new page. Punctuation a match carries at its edges that the
needle does not ("cooled." for "cooled") stays outside (`_trimmed`).

**The answer.** The best-ranked candidate is the highlight's new place
when its confidence reaches the run's `min_match` (0.8 by default). For a
needle shorter than `exact_chars`, it must also be exact. Otherwise there
is no place, and the highlight is lost. An exact match is drawn solid, a
rough one dashed ([What a run writes](#what-a-run-writes)).

A run also asks for the best-ranked candidate under the threshold
(`find_passage`'s `closest`). It passes `CLOSEST_MIN` (0.5, set by hand),
the lowest threshold an account can set. For a needle of at least
`exact_chars` characters, the same search returns that candidate, marked
`below`, when it reads at least that alike. It places nothing: the
highlight is lost and keeps it as its `closest`. A shorter needle has only
exact candidates, which are applied, so it never gets one.

**The rectangles.** The glyphs the match spans give one box per line of
MuPDF's. Two lines sharing a baseline (a span of math inside a sentence)
merge (`rects_for_span`). A passage found piece by piece gets each piece's
boxes, and nothing of what lies between them. Each box goes to the
viewer's frame through `pdf_point_to_viewer` and the page's frame
(`GlyphPages.frame`). That frame is the rect MuPDF shows, with the page's
/Rotate. The rect is the crop box clipped to the media box, taken back to
the glyphs' space through the same matrix the glyphs use. The frame's size
is the page as shown (`display_size`: the crop box, its sides swapped on a
quarter turn). So a rotated or cropped page places like pdf.js draws it.

A position on the old page within half a percent of the page of the old
one (`SAME_PLACE`) is in the **same place**. That holds only for a
highlight not lost. Any other is in a new place.

Three kinds of match also rewrite the highlight's `quote`, even in the
same place:

- A passage found roughly (a confidence under 1.0).
- A part across a page break.
- One found from a pending highlight's `prev`, or for a lost one
  ([The reference](#the-reference)).

The new quote is the text as the new file spells it, since that is what the
highlight covers now. It is written when it differs from the stored one.
As the file spells it means the characters of the glyphs the text was read
from, in the file's case (`PageText.original`). Whitespace and line ends
become one space. A word hyphenated at a line's end is one word, as the
page's text joins it ("Entanglement", never "Entangle- ment"). The pieces
of a passage found piece by piece are joined as their stretches are
(`_spelled`).

**The cost.** The matching is pure Python and holds the GIL. So it naps
for 2 ms after every 10 ms of matching (`_breathe`, `NAP`, `NAP_EVERY`).
The server's requests go on during the naps. It reports a bare progress
once per page the rough, page-break and piece-by-piece searches cover, and
before it reads a page's pictures. So a stop lands within a page. A file's
pages past `pdf_text.MAX_PAGES` (5000) are not read, and a highlight there
is a passage not found.
[research/highlight-anchoring.md](../research/highlight-anchoring.md#measurements)
gives what a run costs in time and memory, and the requests it slows.

## Areas

An area highlight (`area: true`) has no quote. Three things can place it,
tried in this order: the text inside it, the picture it covers, and its
page's other highlights.

**The text inside it** places it when its own file is at hand and the
needle read there is long enough. That is at least twelve characters and
two words (`AREA_MIN_CHARS`, `AREA_MIN_WORDS`): a caption, an axis's
labels, an equation's letters. The passage is found as above, only as a
whole: a part across a page break would put the box askew. The box is put
around the match as it sat around the old anchor (`_box_by_offsets`). It
keeps the same offsets, as fractions of the page, clamped to the page. The
match's confidence decides as a text highlight's does: solid when exact,
dashed with its `match` when rough.

**The picture it covers**, in its own file, places it next. That is an
image the PDF itself draws, not a picture placed on the page
([pictures.md](pictures.md)). It must be drawn under at least half of the
box, or have at least half of itself under the box. It is known by MuPDF's
digest of its data with its pixel size (`GlyphPages.images`). It is found
where the new file draws the same data. When the file draws it more than
once, the page nearest the old one wins. A figure embedded again by pdfTeX
is the same picture, and a publisher's re-encoded figure is another. The
box follows the picture by offsets, solid.

**Its page's other highlights** come third. They are every highlight placed
by its text or picture, exactly or roughly, from the same old page of the
same file. That file is the `doc_id` its position names, or the new file
for one that names none. A shift measured from another file's page says
nothing of this one. A kept highlight counts as no shift, and lost ones
count for nothing. A shift is of the centre, as a fraction of the page.
They must all have landed on one new page, their shifts agreeing within
15% across and down (`RIDE_SPREAD`). The area then goes to that page,
shifted by their median. This is an estimate: `approx` without `match`,
drawn dashed. A median that leaves it on its page within half a percent
leaves it where it is, counted as kept.

**Nothing placed it** when it has no neighbours, or neighbours on two pages
or disagreeing. So too when their shift would carry an edge of it off the
page. An area read from the page's new file, made on it, stays as it is,
counted as kept. One measured on another file stays where it was, with its
rectangles and its `doc_id`. They are still that file's measurement, so a
later run reads it from that file again. It is marked `approx` without
`match` and counted so, drawn dashed for the person to check. It is
written once, its `prev` those same rectangles on that file. An example is
an area over a plotted figure, with neither text nor an embedded picture
to go by, on a page whose text reflowed.

It stays so until the person accepts it. Accept drops its `doc_id` with
the rest, so its rectangles are the current file's. A later run on that
file leaves it be. The next replacement stamps it with the outgoing file,
from which it rides along with its neighbours.

A pending area (dashed, with a `prev`) is placed by the same steps from its
reference. They read the text and the picture under `prev`'s rectangles in
`prev`'s file, then the highlights placed from that file's page. When those
place it nowhere, it rides along, as an estimate, with the highlights
placed from the page and file its rectangles are on now. Those are the
ones an earlier run moved there. Their staying put leaves it as it is,
confirming nothing. Without such a ride, an area whose rectangles are
another file's goes back to its reference. It takes `prev`'s page and
rectangles and `prev`'s file as its `doc_id`, still `approx` with that
`prev`. So it stops naming the file an earlier run guessed it on. On the
page's own file it stays as it is.

An area placed by estimate (`approx` without `match`) stays an estimate
until the person accepts it, whatever places it. The text or picture under
an estimated box is only what the estimate happened to cover. So finding it
again confirms nothing and gives it no `match`. Its neighbours all staying
at its reference put it back there, still `approx` with its `prev`. Like
every dashed highlight, it is pending until the person accepts it or moves
it ([The reference](#the-reference)).

## The thresholds

Two numbers decide what the rough search may apply. One is the confidence
from which the best place is applied (`ROUGH_APPLY`, 0.8). The other is the
length of a folded needle, or piece, under which only an exact copy counts
(`ROUGH_MIN_CHARS`, 12 characters). Both are set by hand, not calibrated.
They are the defaults. An account sets its own in Settings → Reading &
editing › Replacing a PDF ([settings.md](settings.md) "The Settings
dialog"):

- **Apply a rough match from** (`relocateMinMatch`): a percentage from 50
  to 100 in steps of 5, default 80, a `Stepper` with Reset. At 100% only an
  exact copy moves a highlight.
- **Passages shorter than this must match exactly**
  (`relocateExactChars`): 0 to 60 characters, default 12, a `UnitInput`
  committed when it loses the focus. At 0 every passage may match roughly.

Both are account preferences in `PREFS`, kept in the account's profile and
synced as the others are. Their codecs are `stepClamped(50, 100, 5)` and
`intClamped(0, 60)`. A value between two steps reads as the nearer one, a
value out of range as its end.

When a run starts, by a replacement or by hand (`_start_relocation`), the
server reads both from its owner's profile by the same names
(`db.get_profile`, `highlight_relocate.thresholds`). It reads a number as
the codec does (83 is 85, 400 is 100, −3 is 0). It cuts it to a whole
number, puts it on its step from the minimum and pulls it into its range.
A missing value, or one that is no number (a string, a bool), is the
default, each preference on its own. The job's params record them
(`min_match` as a fraction from 0.5 to 1.0, `exact_chars`). `relocate`
passes them through `place_all` to every `find_passage` and to the piece
search, and the result echoes them. So a setting changed later leaves the
runs already started or done as they are.

A token's replacement goes by its account's profile. A guest's account
preferences stay in its browser ([settings.md](settings.md) "Where
settings are stored"), so a guest's runs go by the defaults.

## What a run writes

A run leaves each highlight in one of three looks, and counts it by the
look it leaves (`place_all`'s `kinds`).

**Solid.** The highlight was placed by an exact match of its reference, or
by the PDF's picture under an area. Or it was left where it was made: a
highlight on the page's own file over its quote, or an area there that
nothing moves. Its position carries no `approx`, `match` or `prev`. A
pending one placed solid is then resolved. In a new place it counts as
`moved`, and so does a lost one found again, even where it was. In the
same place it counts as `kept`. It is then written only to drop a
`doc_id`, a flag or a stale `match` or `prev`, or to respell its quote.

**Dashed** (`approx: true`). The highlight was placed by a rough match,
with `match`, also when it lands where it was (a sentence reworded in
place). `match` is its confidence rounded to two decimals and at most
0.99, since 1.0 is an exact match's. An area placed by estimate, or one
measured on another file that nothing placed, is dashed without `match`.
It counts as `approx`.

**Hollow** (`lost: true`). Nothing found it, or its best place reads too
unlike it: under `min_match`, or not exact for a needle under
`exact_chars`. It keeps its old rectangles and its `doc_id`, and a pending
one goes back to its reference ([The reference](#the-reference)). A text
highlight also keeps its `closest` place (below). It counts as `lost`. One
already lost is written again only when its `closest` changes: another
place, or none any more. A run that finds it later, even in the same
place, writes a position without the mark and without `closest`. So does a
move by hand.

**Where it was before.** Every placement a run draws dashed also stores
`pdf_position.prev`, where the highlight was before, for the person to
compare (`highlights.previous_place`):

    {"doc_id": "<the file its rectangles were measured on>", "pageNumber": 1,
     "rects": [[0.1176, 0.1136, 0.4902, 0.1313], …], "quote": "…"}

- `doc_id`: the file the run read the highlight from: its position's
  `doc_id`, else the page's new file it was measured on. It is written
  even when that file is no longer stored or is `unstored`, and the card
  then shows the quote alone.
- `pageNumber`: its page there.
- `rects`: its rectangles as fractions of that page as shown
  (`[x1, y1, x2, y2]`, top-left origin, clamped to the page, four
  decimals, at most 200). A position without a frame's size has none.
- `quote`: its stored quote before the run respelled it (`""` for an area,
  at most 1000 characters).

A pending highlight placed dashed again keeps its own `prev` as it is. So
`prev` shows where it was before the run that first drew it dashed, the
place later runs match it from. An exact placement, a kept one and a lost
one get no `prev`. A pending one placed solid or lost loses its own.
`highlights.from_scaled` keeps `prev` only in this form (`valid_prev`).
That is a string `doc_id`, a positive whole page and at most 200
rectangles of four numbers from 0 to 1. Its quote is a string, clipped to
1000 characters. `from_scaled` keeps `match` only as a number from 0 to 1.

**Its closest place.** A lost text highlight's best candidate may read
under the threshold but at least `CLOSEST_MIN` (0.5) alike
([The answer](#finding-a-passage)). The highlight then keeps that
candidate as `pdf_position.closest`, for the person to look at and take on
its card (`closest_place` in `place_all`):

    {"pageNumber": 2, "width": 612, "height": 792,
     "boundingRect": {"x1": 72, "y1": 300, "x2": 540, "y2": 328},
     "rects": [{"x1": 72, "y1": 300, "x2": 540, "y2": 314}, …],
     "match": 0.72, "quote": "…"}

It is a place in the page's new file, built as a placement's position is:
the match's spans through `rects_for_span`, in the new file's frame.
`match` is its confidence rounded to two decimals and at most 0.99, as a
dashed placement's. `quote` is its text as the new file spells it
(`_spelled`), at most 1000 characters.

The run writes it only in the form `highlights.valid_closest` keeps. That
is a positive whole page, a positive width and height, a placed
`boundingRect` and one to 200 placed rectangles, bare. `match` must be a
number from 0 to 1, and the quote a string clipped to 1000 characters. So
a candidate of more than 200 rectangles gives none. `from_scaled` keeps a
well-formed `closest` and drops a malformed one.

A later run that leaves the highlight lost replaces `closest` with its own
candidate, or removes it when there is none. A run that places it, or
finds it where it is on its own file, removes it. A replacement of the PDF
drops it too ([Which file](#which-file-a-position-was-measured-on)).

**The counts.** The result's counts add up to `highlights`:

- `moved`: solid, in a new place.
- `kept`: solid, where it was.
- `approx`: dashed, whether matched roughly, placed by estimate, or
  pending still.
- `lost`: hollow.
- `changed`: left as a person changed it while the run matched
  ([The job](#the-job)).

### The reference

A highlight that is `approx` when a run starts is pending: drawn dashed,
and not yet accepted. Its own place is a guess nobody approved, so a run
does not match it from there. Each highlight has a **reference**, the last
place someone confirmed: its file, page, rectangles and quote.

- A solid highlight's reference is its own position. It was placed
  exactly, accepted, moved by hand (a selection, **Move…**), or put on its
  closest match (Use closest match writes it solid).
- A dashed one's is its `prev`, the place before the run that first drew
  it dashed (`_Seen.ref`). So is that of a highlight an older run left lost
  while pending: `lost`, `approx` and a `prev`, its rectangles a later
  file's.
- A lost one's is its own position: its rectangles, `doc_id` and quote are
  the place it was confirmed at.

So highlights on one page can have different references, one the second
file, another the first. `reference_place` builds a reference from `prev`.
It puts `prev`'s fractions in the frame of `prev`'s file's page, else in
the position's own frame, and names `prev`'s file.

A run reads each highlight from its reference. For a pending one it reads
the passage, context and anchor from `prev`'s file at `prev`'s rectangles
(`read_from(prev.doc_id)`). When that file is no longer stored, it reads
`prev`'s quote alone. A quote `prev` clipped to 1000 characters is not
compared with the text under the rectangles. With the file gone, that
quote is looked for as it is, the start of the passage. The nearest place
is measured from `prev`'s rectangles on `prev`'s page. Then:

- **found exactly**: solid in its new place, without `approx`, `match` or
  `prev`, its quote as the new file spells it. It is resolved.
- **found roughly**: dashed in its new place, `match` its confidence
  against the reference (not against the guess before), `prev` as it was.
- **not found**: hollow at its reference, with no `prev` (the position is
  its reference). The best candidate of the search from the reference
  is its `closest` when it reads at least `CLOSEST_MIN` alike.

A v3 that prints v1's words again gives v1's highlight back solid,
wherever they stand. So does a later file for one left hollow at its
reference, its quote respelled too.

Hollow at its reference means `prev`'s page and rectangles, `prev`'s file
as its `doc_id`, and `prev`'s quote as its quote. The review card's Before
picture is then cut from that file at that place, as for any lost
highlight. The hollow outline is drawn at those fractions of the current
file's page. A `prev` quote clipped to 1000 characters gives way to the
text under `prev`'s rectangles, as `prev`'s file spells it. With that file
gone, the highlight keeps the quote it had, until a later run finds it
exactly and respells it.

A sentence the first file had may be gone from the third. It is then not
dashed onto a sentence of the third that only the second file's guess
reads like.

A highlight without `prev` is matched from its own position. That includes
a pending one stored without a `prev`: found roughly, it gets one, its own
place. Areas follow the same rule ([Areas](#areas)).

Accepting a highlight, moving it by hand, Use closest match and an exact
match write a position without `prev`. Accept drops `approx`, `match`,
`prev` and `doc_id` and keeps the rectangles
([In the web app](#in-the-web-app)). The next replacement then matches the
highlight from that version. The undo of an Accept puts `prev` back, and
with it the reference.

A run over the file it already ran on writes nothing (Find highlights in
this PDF again). A dashed one stays dashed, its `match` read against the
same reference. Nothing is migrated: the rule reads the stored positions as
they are. A stricter threshold set since leaves a rough match under it
hollow at its reference, its rough place offered as the closest match.

The file a reference names stays stored while the reference names it
([Which file](#which-file-a-position-was-measured-on)). v1 stays while a
highlight still waits on it, through v3 and v4. The in-between v2 goes once
the v3 run has moved the pending rectangles off it. v1 goes once every
highlight referencing it is confirmed, discarded or deleted.

## In the web app

**On the page.** The viewer (`PdfPage`) draws a lost highlight hollow: a
dashed line with no fill, on a flipped page too. The line is in the
stronger shade of its colour that underlines a commented passage
(`--hl-dark-line`). Hollow is the inline style's first branch, so it
holds whatever else the highlight is (an area, commented, a link,
pending). Its rects carry the class `pdfHlLost`, which has no style.
The browser tests find a lost one by it.

A pending one (`approx` and not lost, `placedToCheck`) keeps its look
under a dashed edge. An area's border is dashed. A passage gets a 1px
dashed outline in that same stronger shade. It is an outline so that a
commented or link passage keeps its underline, which is a border. The dash
rides inside a shorthand. React rewrites only the style keys that change,
and a shorthand rewritten beside an unchanged border-style would reset the
dash to solid.

A lost or a pending highlight has no title, not even its comment's (a link
region keeps its own). Hovering or tapping it opens its review card, which
says what happened. Exports and the AI read the position as any other: the
flags are the viewer's.

**The review card** (`HighlightReview.jsx`, `useHighlightReview`).
Hovering a pending (dashed) or a lost (hollow) highlight opens a card
anchored to it, after 150 ms. The pointer may cross into the card, which
waits 200 ms as the pointer leaves the highlight. Leaving both closes it,
as the note badge's tip does (`NoteBadge`). Only dashed and hollow
highlights' rectangles listen for the pointer (PdfPage's `reviewable`), so
a page of many highlights costs nothing more.

Touch has no hover. A tap on one opens the card instead of the tap's usual
action, and a second tap on it does that action. `lastPointer` tells a tap
apart, and with it the tap's compatibility mouse events. A press outside
the card and its highlight closes it. A right-click on one, or a finger
held on it, opens the highlight's menu in place of the card, shown or on
its way.

Escape closes the card too, and so does another page or file. So does the
highlight leaving the review without the card's doing. That is its menu's
Accept, a move not started from the card, an undo back to solid, or a
deletion by someone else.

A card opened by a tap, by Previous or Next, from the Document menu or by
a step on it stays until one of those closes it. It takes the focus once
it shows, so the keys reach its buttons. One reached by Previous or Next
focuses that button of its own, so Enter walks on. One gone on to after an
Accept (below) focuses its own Accept when that applies, so Enter accepts
the next. A step keeps the focus on its button, which stays.

The browser takes the focus from what it hides. So a card hidden a moment
drops the focus to the page. That happens while the viewer scrolls to
where Use closest match took the highlight, or back on an undo. It happens
too while the highlight is scrolled out of sight. The card remembers the
control the focus was last on in it, forgotten once the focus goes
somewhere else. Shown again with the focus still on nothing, the card puts
the focus back there, or on the card when that control is disabled. So
Enter presses the step's button again.

A card whose highlight took a step on a card stays while the pointer rests
on another dashed or hollow highlight. Hovering opens no card in its
place.

The card is 360 px wide at most, the window's width less 16 px on a phone,
so its steps fit on one line (below). It is a portal on the shared popup
surface (`.hlReviewCard` beside `.pdfNoteTip` in app.css), fading in as
the tips do. `placeFloating` and `useFloatingPosition` place it
([ui-design.md](ui-design.md#menus-and-popups-open-in-place)):

- under the highlight's rectangles, or above them when there is no room
  below;
- following the page as it scrolls;
- hidden while the highlight is out of the viewer's sight, and shown on it
  again when it scrolls back;
- for a highlight whose page is still rendering, waiting up to four
  seconds for its rectangles, then closing. It waits as long when the page
  is scrolled so far that it drops them.

The whole card hides, through its inline `visibility`. Nothing in it is
set visible on its own. The before picture is hidden until it loads
(`.hlReviewShot:not(.loaded) img`). Then it inherits the card's
visibility, since no rule shows it. A picture set `visible` once loaded
would show through the hidden card, without its rectangles. It would stand
over the page text at the window's edge while the pages scroll under it.

A lost highlight can name a page the PDF does not have. The run keeps its
page number, so a revision that dropped an appendix leaves it past the
last page (`pastLastPage` against the viewer's page count). It is drawn
nowhere, so no hover or tap reaches it. Previous, Next and the Document
menu do, without scrolling. Its card stands in the viewer, centred near
its foot. Under its header is the line "This version of the PDF has no
page 10." (`.hlReviewOffPage`). Its Before picture, a closest match on a
page the PDF has, Move…, Previous and Next are as on any lost card.

**The notes follow the card.** A card that goes to a highlight has the
notes panel scroll to that highlight's block and mark it, as a click on
the highlight does. A card goes to one on a tap, on Previous or Next, on
the advance after a decision and on the Document menu's Review.
PdfViewer's `onReviewFollow` and App's `scrollToBlock` and `reveal` do the
scrolling. The row is scrolled to the middle and marked focused in the
tree's sense, its folded parents opened. No editor opens, and the focus
stays in the card. The chat's Cursor chip follows that row, as after any
jump. Unlike a click, the card does not add the highlight's quote to the
chat. A hovered card leaves the notes where they are. One whose block
Discard deleted has none to go to. On a phone the notes panel is closed
while the PDF shows, so the row is scrolled to when the panel opens, as
after a click.

`reviewCardState` gives what the card shows, from the highlight and the
card's step in effect on it, if any (below).

**A pending (dashed) card** shows:

- the header "Matched roughly: 92%" (`roughMatchPercent`, the position's
  `match`), or "Placed by estimate" for one without `match`;
- **Before**, from its `prev` (`reviewPrev`, below);
- for a text highlight, its old quote against its current one, word by
  word (below);
- its **Note** (below);
- for an editor, **Accept** (the primary button), **Move…** (move mode,
  below) and **Discard** (below);
- under them, **Previous**, "2 of 3" and **Next**.

**A lost (hollow) card** has the header "Not found in this version". Its
**Before** comes from its own position (`lostPlace`), since a lost
highlight keeps its rectangles and the file they were measured on:

- When its `doc_id` names a file other than the page's current one
  (PdfViewer's `fileId`, App's `attachmentFile`), the picture is cut from
  that file. Its rectangles, turned into fractions of their frame, are
  drawn over it, and its stored quote stands beneath.
- When it names none, or the current file, its rectangles are this file's,
  drawn hollow right there. So there is no picture, only its stored quote.

When the position keeps a `closest` place, the card adds "Closest match:
72%" with **Show**. `closestPlace` reads that place defensively, as the
server keeps it, and takes a missing `boundingRect` as the rectangles'
union. The card also sets the stored quote against the candidate's text,
word by word. That comparison stands in for the quote under the picture,
and on the current file for Before.

Show scrolls to the candidate's page and outlines its rectangles while the
card is open, and the card anchors to the outline. The outline is
`.pdfHlGhost` in app.css: a dotted line in the colour's line tone over a
faint wash. It takes no pointer and has the dark-tuned colour on a flipped
page. A candidate a few pages away is drawn only once its page renders,
after the scroll. PdfPage then says so (`review.drawn`, the hook's
`drawn`), and the card places itself under the outline.

Then come its **Note** (below) and, for an editor, the same row as a
pending card's:

- **Accept**, disabled until Move… or Use closest match placed it, then
  taking that place as the decision (below). Its title is "Nothing to
  accept until it is placed: use Move… or Use closest match first", or
  "…: use Move… first" without a closest match.
- **Use closest match**, the primary button, when there is one;
- **Move…** and **Discard** (below);
- under them, **Previous**, "1 of 3" and **Next**.

Beside Use closest match, Move… and Discard show their icons alone, with
their names in `aria-label` and `title`. Four labelled step buttons do not
fit on one line at the card's width. A lost area has no `closest`.

Every card has one row in one order (`reviewActions`): Accept, Use closest
match (on a lost card with a closest match only), Move… and Discard. So a
dashed card and a hollow one without a closest match have the same buttons
in the same places. No button appears or goes on a step. Previous, the
count and Next stand on a line of their own under the steps on every card
(`.hlReviewNav`). The steps and those three together do not fit one line
at 360 px. The primary button is the step the card suggests: Accept while
it applies and is not taken, else Use closest match.

**The note** (`noteSummary`) stands under the quote: the comparison, or a
lost card's stored quote. App's `reviewNote` reads it from the live block,
so an edit shows at once. It is the block's text as the words a reader
sees, through search's `plainSnippet` line by line: markdown's marks, refs
and tags go. It shows four lines at most, which scroll
(`.hlReviewNoteText`). Under them come its counts, when there are any
(`.hlReviewNoteCounts`): "2 notes under it · 1 comment". They count the
blocks under the highlight at any depth and its comment threads, open or
resolved, as Discard counts them. A highlight that carries none shows
nothing there. One whose block Discard deleted shows the note the card had
(`h.note`, recorded with the step). It carried nothing, or Discard would
have kept it.

**On a short window** the card is at most `min(70vh, 520px)` tall. It
takes the room it lacks from the note first, down to its label, one line
of its text (the rest scrolls) and its counts. Then it takes room from a
dashed card's word comparison, down to three lines or all of a shorter
one. `Diff` sets the comparison's content height as `--hl-review-diff-h`.
The header, the Before picture, a lost card's closest match, the steps and
Previous / Next keep their size. A window too short for those floors
scrolls the card as a whole: under about 660 px with a Before picture of
several lines.

**After a step** taken on the card, the card stays as it was. The steps
are Accept, Use closest match, a move started from its Move…, and Discard.
The header, Before picture, comparison and closest match stay, and so do
the buttons in their places. Nothing on it moves: the Playwright suite
compares every box within a pixel. The card is drawn from where the
highlight stood before the step, with its quote then (`reviewCardState`
over `stepOrigin`). That place is the position of the step's `before`,
through a move's `prior` to the step before it. The quote is the one kept
by the first step that rewrote it.

Only the step's button changes, in place:

- It reads "Accepted", "Placed", "Moved" or "Discarded" with a check.
  Accept and Use closest match show no icon before, the check marking a
  step done.
- It is pressed (`aria-pressed`, the `.uiBtn.on` look).
- Its title says that pressing it again undoes the step ("Accepted: press
  again to undo").

Pressed, the icon-only Move… and Discard of a lost card with a closest
match (above) show the check alone. "Moved" or "Discarded" is then their
`aria-label` and the start of their title. Each step button holds both
its faces in one grid cell, the one not shown hidden (`.hlReviewFace`,
`.hlReviewRoom` in app.css), so it keeps one width either way.

A button that does not apply stays in its place, disabled
(`reviewActions`):

- a lost card's Accept before anything placed it (`unplaced`);
- after Discard: Accept, Use closest match and Move…, since nothing is
  left on the page;
- after any other step: Use closest match and Discard, which are for a
  highlight still to check.

Accept stays usable after a move and after Use closest match, which wait
for it. Move… stays usable after Accept and Use closest match: a move from
there keeps the earlier step to come back to (below). Show is disabled
while a step is in effect.

The card follows its highlight when the step took it elsewhere (Use
closest match, a move). A discarded highlight is drawn no more. Its card
shows it as the card last had it and stays where it stood, anchored to its
old rectangles on its page. `placeBox` places those from the page's box on
screen, the way PdfPage scales them. The card follows the page and hides
out of sight as any card does.

**A decision goes on to the next one.** Accept and Discard decide a
highlight (`decided`): Accept on a dashed card, or after a move or Use
closest match on the card. A move and Use closest match alone do not
decide: they wait for Accept. After a decision the card shows its pressed
button for a moment (`ADVANCE_MS`, 600 ms). Then it goes on, as Next
would, to the next one still to check in the walk, the notes following
(above). That one is `nextToCheck`: the first undecided entry after the
decided one in document order, round from the last to the first. After an
Accept, the next card's Accept takes the focus when it applies.

The moment starts once the decision is the card's step in effect. It ends
with nothing done:

- when anything on the card is pressed (a capture-phase click on the
  card), the decision's own button, which undoes it, among them;
- when the card closes;
- when the step in effect changes by other means (Ctrl+Z, a collaborator's
  edit).

When none is left to check, the card stays and its count reads "All
reviewed". The decided one stays in the walk, so Previous reaches it and
its pressed button undoes it. The Document menu's Accept all opens no
card.

A step is in effect only while the highlight is as the step left it
(`stepInEffect`). For Accept, Use closest match and a move, its stored
position is the one the step wrote (App's `reviewPosition`, the block's
`pdf_position`, null for no block). A Discard is in effect while the
highlight is drawn no more and its block is as the step left it. A deleted
one's block is gone, and one kept as a note keeps the `{pageNumber}` the
step wrote. The app's own undo (Ctrl+Z), someone else's change or a move
by hand elsewhere ends the step. The card then shows the highlight as it
is:

- dashed or hollow again: its buttons as before any step;
- solid somewhere else, or deleted by someone else: nothing to review, and
  the card closes.

The card shows the newest of its steps still in effect (`reviewList`, back
through a move's `prior`). Ctrl+Z after a move started from an accepted
(or placed) card takes back the move alone. The highlight is then as
Accept left it, still in the walk at its place, and the card shows
Accepted pressed. Ctrl+Y redoes the move, and the card shows Moved again.

A viewer who may not edit sees the card without actions (Show stays).

**Before** is a picture of the highlight in the file it was measured on.
It comes from
`GET /api/ai/selection-crop/{doc_id}?page={pageNumber}&box={x1,y1,x2,y2}`,
the chat's picture of a region, open to any workspace member and cached
([api.md](api.md) "AI (`ai.py`)"). The box is the union of the old
rectangles, widened by about two lines of text and kept on the page
(`cropBox`). The margin is 2.4 lines. A line is a text highlight's median
rectangle height, kept within 0.6% to 3% of the page, or 1.4% of the page
for an area. The margin is as wide across in points as down. The old
rectangles are drawn over the picture in the highlight's colour where they
fall in the crop (`rectsInCrop`). While it loads, a placeholder of the
crop's shape stands in (`cropAspect`). The picture takes the page's paper
and the theme's treatment of it, flipped on a flipped page, as the
viewer's pages and the zoom window do.

When the picture cannot be had, one line says "The previous version of
this PDF is no longer stored." The old quote follows, ending in "…" when
clipped (below). A file not stored answers 404. A highlight that holds its
file meets that only for a file purged before pending highlights held
their files, or removed some other way. A `doc_id` that is no file name,
such as `unstored`, asks for no picture.

A share view asks for no picture (`beforeCrop`). The route is a workspace
member's and reads no share token, and the asker chooses its box. Opening
it to a share link would let one cut whole pages of a file the owner
replaced. The card's line says "The previous version is not shown through
a share link.", and the old quote follows.

**The comparison** sets two quotes against each other, word by word
(`quoteDiff` over `shared/lib/wordDiff.js`, the merge conflict card's word
diff). The words gone are struck through and the words added marked, as
the chat's approval card marks them. Whitespace reads as one space. An
unchanged stretch beside a change is cut to six words (`DIFF_CONTEXT`).
Two empty quotes show nothing.

A pending card's old quote may be as long as a `prev` keeps: 1000
characters (blockModel's `PREV_MAX_QUOTE`, `reviewPrev`'s `clipped`). It
is then the start of a longer passage, cut wherever the thousandth
character fell. It is compared up to its last whole word, and the current
quote only as far. The rest of the current quote is not marked added, save
the words that replace old ones struck at the cut. An ellipsis ends it. A
change past the cut is not shown, but the picture shows the old passage. A
lost card's stored quote is whole.

**The review queue** (`reviewQueue`, `placeToReview`) holds the open
page's pending and lost highlights in document order. That is by page,
then top, then left, as `pendingInOrder` orders the pending ones for
Accept all. They are the ones left to check, which the Document menu's
Review counts and opens the first of, with those waiting for Accept on a
card (below). A lost one past the PDF's last page sorts last.

**The card's walk** (`reviewList`, `reviewPlace`) is what **Previous**
and **Next** walk and "{i} of {n}" counts, in the same order. It holds the
queue, and the highlights resolved by a step on a card while the viewer
has shown this page and file. Each stands where it stood before its step
(`stepOrigin`). So a step changes neither the count ("2 of 3" stays "2 of
3") nor what Previous and Next reach.

The steps are the viewer's (`useHighlightReview`'s `steps`). Each maps the
highlight's id to the step last taken on its card and the highlight as the
card had it. A move from a resolved card, and an Accept after a move or
Use closest match, keep the card's step before them as their `prior`. The
card records Accept, Use closest match and Discard. App records a move
from the card (`reviewRef` with `done`). Another page or file (a new
replacement) or a reload starts them afresh (`review.reset`, on
PdfViewer's `url`, `fileId` and `pageId`).

A resolved highlight is in the walk on the newest of its steps still in
effect (above). One with no step still in effect is there as what it is:
to check again, at its place. Solid elsewhere, or deleted by someone else,
it leaves the walk. One the card deleted by Discard stays, as the card
shows it, until the walk starts afresh.

Previous and Next scroll to the highlight and open its card, round from
the last to the first and back. A discarded one is scrolled to where it
stood (`entryPosition`). Both are disabled when the walk holds one
highlight. A resolved one opens with its step's button pressed, which
undoes it.

The entries left to check are those not decided (`reviewPlace`'s `left`).
They are those with no step in effect, and those moved or placed on a card
that wait for Accept. When none is left, the count reads "All reviewed" in
its place. The waiting ones (`waitingForAccept`) are solid already, so they
are not in the queue. PdfViewer tells App their ids (`onReviewWaiting`).
The Document menu's Review counts them with the queue (`reviewQueue`'s
`waiting`, the same number as the card's `left`). Waiting lasts as long as
the walk. On another page or file, or after a reload, a highlight moved or
placed on its closest match without Accept counts as resolved. It stays
where the step put it.

The count holds the room of both its texts, so the last step moves
nothing. Previous and Next are `ctlBtn` chevrons on either side of the
count, as the merge resolver's conflict card walks its conflicts
(`.mergeNav`). The steps stay on one line in English and Chinese (whose
count is "2 / 3") down to a 360 px phone. They wrap only under a larger
interface font. A window narrower than 376 px makes the card narrower than
its 360 px. There its sides, the steps' gaps and the labelled buttons'
padding are tighter (app.css, the `max-width: 375px` rule after
`.hlReviewCount`). Below 360 px they may wrap.

**Accept** (App's `acceptHighlightPlaces`, `acceptedPosition`) is the
card's Accept, the highlight's context menu entry and the Document menu's
Accept all. The context menu offers **Accept** to an editor on a pending
highlight, before **Move to a selection…**. Each removes `approx`, `match`
and `prev` from the position and keeps its rectangles, so the highlight is
drawn solid where it is. Each removes `doc_id` too. Accepting says the
rectangles are right on the PDF the page shows, so they name no other
file. Until then an area nothing placed names its old file. A later run
neither reads the rectangles from that file nor dashes them again. A run
under way for that file skips the position, changed meanwhile, by its
compare-and-set. Accept is a tree edit like any other, one for any number
of highlights: one undo step, sent to the others as ops.

The card's Accept leaves the card open on the highlight as it was, its
Accept reading "Accepted", pressed. A moment later the card goes on to the
next one to check (above). On a card whose move or Use closest match
placed the highlight (solid already), Accept writes nothing. It takes that
place as the decision (`reviewStep`'s `confirms`, its `after` the position
as it is, its `prior` that step). Its title is then "Keep the highlight
where it was placed".

**Use closest match** (App's `placeClosestMatch`, `closestPlacement`)
gives the lost highlight the candidate's place and text. Its position
becomes the `closest` one (`pageNumber`, `width`, `height`,
`boundingRect`, `rects`), and its quote the candidate's quote. So `lost`,
`closest`, `doc_id`, `approx`, `match` and `prev` go with the old
position, and it is drawn solid. It is a tree edit like any other:
undoable, sent to the others as ops. The card scrolls to the new place and
follows the highlight there, as it was, its Use closest match reading
"Placed".

**Discard** (App's `discardReviewHighlight`, with `discardKind`,
`discardOutcome`, `discardStep` and `discardApplied` in
highlightReview.js) follows Move… on a dashed or a lost highlight's card,
for an editor. It is for a highlight whose passage is not in the new file
as it was, which the person does not want placed.

- A highlight that carries nothing of its own is deleted, as the highlight
  menu's Delete deletes it (`removeBlockTree`). It has no note (its
  content empty or spaces), no child blocks (even empty ones) and no
  comment thread, open or resolved (`comments.threadsByBlock`).
- One that carries any of those stays as a note and comes off the PDF.
  Its `pdf_position` becomes its page alone, `{pageNumber}`.

A page alone is the shape of a highlight whose place on its page is not
known (a Logseq import's). So nothing draws one kept as a note, and the
review queue leaves it out. Its rectangles, `lost`, `closest`, `doc_id`,
`approx`, `match` and `prev` go. Its quote, colour, note, children and
threads stay. Its row is such a highlight's: a grey dot ("Jump to page (no
exact position)"), "p.1" and the ⊕ that attaches it to a highlight.

Either way Discard is one tree edit (ops, undo). The button's title says
which it will do, on a dashed card as on a lost one:

- "Delete the highlight: it holds no note or comment, and its text is not
  in this version"
- "Take the highlight off the PDF and keep it as a note, with its quote,
  note and comments"

App's `discardOf` picks the title (`discardOutcome` over what the block
carries), so the title holds after another step too. The card then stays
as it was a moment, its Discard reading "Discarded" (above), and goes on
to the next one to check. There is no Discard in the highlight's menu or
in the Document menu. The menu's Delete does the first, and its Move to a
selection… undoes the second. Discarding goes one highlight at a time,
after a look at its card.

**Moving a highlight** (App's `moveHighlightId`) starts in three places,
for an editor. A highlight's context menu on the page offers **Move to a
selection…**, a lost row has its button, and the review card has
**Move…**. Move… closes the card to make way for the selection. Move mode
and attach mode (a note's ⊕ waiting for a click on a highlight) end each
other (`startHighlightMove`, `startAttachMode`). So one banner shows, and
the PDF's next selection means one thing.

The banner says what to do. Meanwhile the highlights let the pointer
through (`.pdfMoving`), so the text under them can be selected, the
highlight's own included. The next selection on the PDF is text or an
area drag (the area key, [hotkeys.md](hotkeys.md#held-keys)). It shows the
tip with one button, *Move the highlight here* (`PlainTip`'s `onMove`). It
translates nothing, even with translate on select on. An area drag sends
no picture of the region to the chat either (`PdfViewer`'s
`onAreaSelected` skips `onAreaSelection` in move mode).

`moveHighlightTo` sets the highlight's `pdf_position` to the selection's,
with no `doc_id`, `lost`, `closest`, `approx`, `match` or `prev`. It sets
its `quote` to the selection's text. An area drag makes it an area with no
quote, as one made the usual way. The move is a tree edit like any other,
so it is undoable and reaches the others, and the highlight flashes.
Escape, Cancel, another page, or another file on this page ends the mode.
The mode serves any misplaced highlight, not only a lost or a pending one.

A move started from the card's Move… is remembered (`startReviewMove`,
`moveFromReviewRef`). The ref is `{id, prior}`, `prior` being the card's
step in effect when it had one. Any other start of a move forgets it. Once
the selection has moved the highlight, `moveHighlightTo` opens the card on
it again through `reviewRef`, which records the step. The card is as it
was, its Move… reading "Moved", pressed, and its Accept there to take the
new place. It does not scroll, since the selection is in sight.

**Undoing a step** is its button pressed again ("Accepted", "Placed",
"Moved", "Discarded"). There is no Undo button of its own. Each step is
one tree edit (`acceptHighlightPlaces`, `placeClosestMatch`,
`moveHighlightTo`). The card keeps what it changed (`reviewStep`:
`{kind, before: {position, quote?}, after}`). `before` is the position
before the step, with the quote for a placement or a move. `after` is the
position it wrote. The undo (App's `undoReviewStep`, `reviewUndone`) is
one more tree edit, so the app's own undo takes it back in turn. It puts
that position back, and for a placement or a move that quote, an empty
one dropped. It does nothing when the highlight's position is not the one
the step wrote: changed since, by an undo or by someone else.

The card is then as it was before the step, nothing on it moving. After
an Accept's undo it is pending again. After a placement's or a move's
undo it scrolls back to where the highlight was.

A move started from a card whose step was in effect ("Accepted",
"Placed") keeps that step as its own `prior`. It does so while the
position it moved from is the one that step wrote. Its undo leaves the
card on that step again, its button pressed. So after Accept and a move,
Moved and then Accepted pressed in turn each go back one step, to the
highlight pending. An Accept that took a move's or Use closest match's
place (`confirms`) wrote nothing, so its undo writes nothing either
(`undoReviewStep` returns). The card is back on that step, Moved or Placed
pressed, whose own undo is there.

A Discard's step is `discardStep`'s
`{kind: "discarded", deleted, before: {position}}`. For a deletion it adds
`removed`: the block as it was and its place among its siblings
(`blockPlace`). Otherwise it adds `after`, `{pageNumber}`. `discardUndone`
takes it back:

- A deleted highlight is inserted again where it stood (`insertBlockAt`),
  the same insert of its id that the app's own undo of a deletion sends.
  When its parent has gone since, it goes at the end of the page's top
  level.
- One kept as a note gets its dashed or lost position again
  (`reviewUndone`).

Either way it is back on the page, dashed or hollow as it was, where the
card stands. The card is as it was before the Discard. The undo does
nothing when the deleted one is back already, or when the kept one's
position changed since.

**The Document menu.** When the open page has highlights to review, the
paperclip popover lists two rows:

- **Review 3 highlights** (**Review 1 highlight** for one), for anyone.
  Its title is "Show the first dashed or hollow highlight with what it
  matched and where it was before".
- **Accept all 3 rough matches** (**Accept 1 rough match** for one), for
  an editor while some are pending. It is one Accept over all of them, and
  the status says how many when there are several.

Review closes the menu and shows the PDF if it was hidden. It scrolls to
the first of the review queue and those waiting for Accept on a card, in
document order of their current places. It opens that one's card. A waiting
one's card opens with its step's button pressed and Accept enabled. Review
counts the pending and the lost ones left to check, and those moved or
placed on a card that wait for its Accept. It leaves out the ones decided
on a card, which the card's walk keeps. Accept all counts the pending ones
alone, an estimated area included. A discarded one is in neither, and
there is no Discard over them all.

**Find highlights in this PDF**'s title says what a run does: "Find each
highlight's text in this file and move the highlight there; one matched
roughly is drawn dashed, one whose text is gone hollow".

**The notes row.** A lost row's dot is hollow (`.highlightDot.lost` in
app.css, its colour the inline `border-color`), and its title says so. For
an editor on a page with a PDF, a button beside it starts a move. It is
among the row's hover controls, like the ⊕ of a note without a highlight.
A pending row's dot keeps its fill inside a dashed ring in its colour's
line tone (`.highlightDot.approx`, `darkPageLine`), as the viewer draws
it. Its title is "Matched roughly (92%) after the PDF was replaced — jump
to it", or "Placed by estimate after the PDF was replaced — jump to it"
for one without `match`. A click on either dot jumps to the highlight, as
any dot's does.

**What the tab says.** The run's Background tasks row reads "Placing the
highlights of “…”". Its phases are "Reading the PDF" and "Finding each
highlight's text", with a stop button until it writes. The tab that
started the run says what it found when it ends (`relocationSummary`). A
tab starts a run by a replacement (whose answer it adopts), by the menu or
by Start again. The summary holds:

- "Highlights placed in the PDF: 2 moved, 1 matched roughly or estimated,
  5 stayed in place, 1 not found.";
- when one is dashed, "Hover the dashed highlight to compare and accept
  it.", or for several "Hover a dashed highlight to compare and accept it,
  or review all 3 from the Document menu.";
- how many someone moved meanwhile (`changed`);
- when one is lost, how to move a hollow highlight.

A count missing from a result written before it existed reads as 0. A run
that failed or was stopped says so, unless the PDF was replaced again
meanwhile. A run for another file of the same page, started after it,
means the replacement stopped it or had its write refused.
`taskKinds.relocationSuperseded` reads that from the listing the run ended
in. The newer run reports instead. While one of the account's runs for
the page is going, **Find highlights in this PDF** is disabled. Its title
is "This page's highlights are being placed — see Background tasks".

## Limits and open items

- The thresholds were set by hand, not calibrated, and the character
  likeness is generous. Both of these would be placed, dashed, at the
  default 80%, which is what the dashed look and the card are for:
  - "one dos tres cuatro five … ten" against "one two three … ten" reads
    0.87;
  - "a doublet at the resonance … when the second beam is switched on"
    against "a single line below the resonance … when the first beam is
    switched off" reads 0.83.
- An exact copy is taken, solid and not pending, wherever it stands:
  - A passage reworded in place to under about 0.86 alike
    ([The ranking](#finding-a-passage)) moves to a verbatim copy elsewhere
    without a prompt. An abstract's sentence goes to the conclusion that
    restates it, and a term to its next use.
  - A short passage that names something else in the new file does the
    same. "Fig. 3" lands on the new Fig. 3 after the figures were
    renumbered, at any threshold, since the new file holds it word for
    word.
  - The surroundings cannot tell such a copy from an ordinary revision, so
    a rule on the context fit would dash correct placements too. An
    unchanged sentence beside an inserted or reworded one fits its old
    context about as poorly (0.2 to 0.35) as an unrelated copy does (about
    0.32).
  - Telling them apart would take evidence that the old place survives
    with other words in it, and is open. That evidence is both sides of
    the old context found next to each other, around different text.
- The threshold is applied to the best-ranked candidate alone. At a high
  `min_match`, a rough candidate that its context ranks above an exact
  copy elsewhere can fall under it:
  - The highlight is left lost, not placed on the exact copy.
  - Its card offers that rough candidate as the closest match, not the
    copy.
- The rough search's bound and its pre-filter are for speed, but a place
  past them is no candidate. At a threshold lowered toward 50%, a place
  reading 0.6 or 0.7 alike may never be offered, nor kept as a lost
  highlight's closest match.
- A passage is split only where the old file read text between its
  pieces. One the new file splits elsewhere or reads in another order is
  found only as far as the rough search reads it alike, else marked lost:
  - split elsewhere: its lines reflowed, and a caption or footnote drawn
    inside it;
  - in another order: two columns drawn interleaved, line by line.
- Without its file (purged, `unstored`, a lost highlight without
  `doc_id`), the stored quote is the needle and there is no context:
  - Two identical short passages are told apart by page and place alone.
  - A quote the viewer stored with a review copy's margin line numbers
    reads less like a file without them. Each number is a word too many,
    so the quote is placed dashed or, past the threshold, lost.
  - Keeping a passage's context with the highlight when it is made, as a
    `TextQuoteSelector`'s prefix and suffix in the W3C model, would serve
    such runs
    ([research/highlight-anchoring.md](../research/highlight-anchoring.md)).
- A passage under `exact_chars` characters, or of one word, that the new
  file splits at a page break is not found.
- A page whose PDF is shown from a URL alone (no `doc_id`, and an address
  that is not an upload's) has no file a run can read. No run starts for
  it, and its highlights stay where they were.
- Ink groups, text boxes and pictures placed on the page
  ([pictures.md](pictures.md)) stay where they were, though their pages
  may have shifted. Carrying them along with the page's highlights (the
  ride-along rule) is open.
- An area over a vector figure without text and without an embedded image
  rides along, or stays where it was marked `approx`.
- A run's ops are the server's, so no tab's undo history has them. A
  highlight moved wrongly is moved back by hand, and a dashed or hollow
  one's card shows where it was before.
- The passages are read with MuPDF, the viewer's text with pdf.js. A quote
  stored by the viewer that MuPDF reads differently (spacing in a
  mathematical line) is found roughly, drawn dashed, or not at all.

## Tests

`backend/tests/test_highlight_relocate.py` covers what follows.

**The page text**: the folding, hyphenated words, the file's spelling of a
passage, the compact glyph store, number-only lines left out.

**The passage under a highlight**:

- across a line's end;
- across two columns with a caption drawn between them (its two pieces);
- an area over a table's column, measured alike in both files;
- line rectangles stored in a frame a tenth off their scale, which read a
  line low into the next sentence and its citation. The quote's own copy
  places them exactly over its words, not dashed with a rectangle over the
  next sentence.
- line rectangles that run past their column's edge into the next
  column's lines, placed the same way: the sentence, one ending
  mid-sentence and one from the paragraph's start;
- rectangles within the quote, or a quote the page does not hold, read as
  they are.

**The search**:

- an exact passage on another page;
- context, then page, then place;
- the rough search over changed words, with its confidence: a number, a
  citation, a symbol, a negation, punctuation, none frozen;
- a changed number at the passage's edge covered;
- a text too unlike not placed;
- twelve characters enough whatever the count of words, and a changed
  passage under twelve characters lost however alike;
- a dropped first word;
- a word added before a passage's last word or after its first. The whole
  sentence is covered and respelled, and in place it still ranks ahead of
  a verbatim copy elsewhere.
- in the same test, a dropped last word that does not take in the next
  sentence's first;
- word edges;
- a passage reworded in place against an exact copy elsewhere;
- the nearest place measured on the page;
- a passage split by a page break;
- a passage across two columns found piece by piece past the caption:
  shifted, a piece reworded, across a page break, too far from the piece
  before;
- the bound changing no answer, and a page without the needle's words not
  aligned;
- a stop within a page.

**The rectangles** on a plain and on a rotated cropped page.

**What a run leaves**:

- a renumbered citation, a sentence with a few words changed, a changed
  number, and a stray symbol inside the old rectangles of a highlight
  without a quote. Each is placed dashed with its `match`, respelled quote
  and `prev`.
- that symbol's highlight with a quote, found exactly by the quote's copy:
  solid;
- a deleted sentence whose nearest look-alike reads too unlike: lost, and
  placed at a threshold of 0.5;
- a lost highlight keeping its closest place under the threshold: its
  place in the new file, `match` and quote, kept by `from_scaled`. It
  stays lost at the default 80% and at 95%, and is placed dashed at 70%.
- no closest place for a best place under `CLOSEST_MIN`, or for a passage
  under twelve characters;
- a later run on the same file writing nothing;
- another file replacing the closest place, one with nothing alike
  removing it, and one with the sentence placing it solid without it;
- the file the lost highlight was measured on, back as the page's PDF,
  dropping `lost`, `doc_id` and `closest`;
- an exact placement of a highlight not pending: solid, dropping a stale
  `match` and `prev`;
- a dashed placement keeping where the highlight was when that file is
  gone;
- the thresholds read from a profile (in range, on their step, clamped,
  else the defaults) and deciding what a rough match applies.

**The reference**: a highlight left pending on v2 and v3, matched from v1.

- renumbered again: dashed, `prev` v1's, `match` against v1's words;
- v1's words back in place and a line lower: solid, v1's quote;
- the passage gone: hollow at v1's place, naming v1, with v1's quote;
- v1's sentence gone while v2's guess reads like a v3 sentence: the same,
  not on the look-alike;
- v1's text changed further while v2's guess drifted: dashed on the
  sentence like v1's;
- a stricter threshold: hollow at v1's place with its closest match;
- v1's file gone: `prev`'s quote;
- v3 being v1's own file again: solid without a search;
- one an earlier run left lost while pending, and one without `prev`;
- a pending area by its caption and by estimate, sent back to v1's place
  when nothing places it;
- each rerun on v3 writing nothing.

**`place_all`**:

- two files with each outcome;
- a file gone;
- a quote that is not the text under the rectangles, and a short line
  under them that is only a part of it;
- a highlight on the page's own file over its quote, kept without a
  search;
- a sentence reworded in place staying, dashed, with its new spelling;
- a respelled quote with a word hyphenated at a line's end;
- margin line numbers either way;
- a new file without text;
- a lost highlight not written again, and one found again;
- the page cap;
- the ride-along's cases;
- an area placed by estimate staying one through a second run on the same
  file, and after the next replacement (riding along from its current
  place);
- an area with nothing to go by, on its own file and on another;
- such an area, once accepted, left be by the next run on that file and
  carried along at the next replacement;
- an unplaced area not carried by highlights measured on another file.

**The endpoint and the job**:

- the PUT's run and its answer;
- the counts of a run end to end, with the flags stored;
- a run going by its owner's thresholds as they were when it started, in
  its params and result;
- a later run by hand at other thresholds, the first run's result
  unchanged;
- a stricter run sending the pending ones back hollow to the first file;
- two replacements with highlights still pending: dashed again against
  v1, hollow at v1's place, both solid once v4 prints v1's words;
- the stamps (a lost highlight's `closest` dropped with them), and
  `relocation_error` when the jobs are full;
- a run that never wrote;
- a lost highlight read from its own file by the next runs;
- a wrong file in between;
- a `doc_id` not stored, or no file name;
- a page shown from a URL alone and replaced by a stored file;
- a duplicated page: the manual run on it, and a replacement stamping its
  stored file;
- a PDF shown from a URL alone starting no run;
- the manual run after a skipped one;
- a second replacement stopping the run before, and a manual start
  answering the running one;
- a page without highlights;
- the manual run by the stored quotes;
- a token's 403 on the manual start, beside its replacement's run.

**The write**: a highlight a person changes or deletes while the run
matches, the page deleted meanwhile, the batches in one transaction, the
file changed again.

The other backend tests:

- `test_pages.py`'s replacement test checks the stamp.
- `test_upload_retention.py` checks the files a reference keeps:
  - v1 through v3 while a highlight waits on it, claimed by the run from a
    sync's day and kept past it;
  - v2 recorded once the v3 run moved the rectangles off it, with the
    sync's day;
  - v1 recorded on Accept and named again by its undo;
  - v1 still named by the highlight hollow at its place on v4 while v3
    goes, and recorded with the usual 30 days once it is deleted.
- `test_highlight_shape.py` checks `previous_place`'s fractions of the
  page and what `from_scaled` keeps:
  - `match` as a number from 0 to 1;
  - `prev` only in its own form;
  - `closest` only in its own: a valid one, its quote clipped, 200
    rectangles, extra keys stripped, a malformed one dropped.
- `test_restore_consistency.py` checks that a restore keeps `doc_id`,
  `lost`, `closest`, `approx`, `match` and `prev`.

In the web app, `frontend/tests/highlightReview.test.mjs` checks the
card's arithmetic.

**The queue and the walk**:

- the pending highlights in document order;
- the review queue of the dashed and the hollow ones, a solid one left
  out;
- Previous and Next round the walk, both disabled for one alone;
- a step changing neither the count nor the walk. After Accept, Use
  closest match or a move from an accepted card, the highlight stands in
  the walk where it stood before.
- all of them placed: still to check. All of them accepted: "All
  reviewed" (`left` 0).
- `stepOrigin` through a move's `prior`;
- a highlight past the PDF's last page, told apart and kept in the queue,
  last.

**Before and the card's state**:

- `prev` read defensively;
- a lost one's Before from its own place, a picture only of a file other
  than the page's;
- the card's state: pending, and lost with and without a closest match;
- the card after Accept, a move from the accepted card, Use closest match
  and Discard: the same as before the step but for its `step`.

**The buttons** (`reviewActions`):

- one row in one order for each kind of card: dashed, and lost with and
  without a closest match. A dashed one and a lost one without have the
  same set.
- the buttons in their places after each step, the step's pressed, the
  ones that do not apply disabled;
- a lost one's Accept waiting for a place, and usable after a move or Use
  closest match;
- Accept after a move or Use closest match confirming that place, writing
  nothing (`confirms`);
- no `confirms` without such a step, or once the highlight moved on. The
  app's undo of the move leaves it to check.

**Decisions and steps**:

- a decision (`decided`: Accept and Discard, not a move or Use closest
  match) going on to the next one still to check (`nextToCheck`);
- that next one after the decided one in document order, round, past the
  decided ones;
- a moved one waiting for Accept as that next one, and none when nothing
  is left;
- a step in effect only while the highlight is as it left it. After the
  app's undo it is to check again, and a collaborator's move or a deletion
  takes it out of the walk.
- what each step changes, for its undo, and a move from a resolved card
  keeping that card's step as its `prior`.

**The picture and the comparison**:

- the picture asked of the old file, and not through a share link;
- the crop's box and the old rectangles in it;
- the quote comparison, and a long passage compared quickly;
- an old quote clipped to a `prev`'s length, compared only as far as it
  goes.

**Discard**:

- which of the two it does: a note of spaces is no note, and an empty
  child or a thread keeps it;
- a lost or a dashed one discarded and no other, `discardOutcome` whatever
  the place;
- on a dashed one, a bare one deleted and put back dashed with its `prev`,
  its card the dashed one's with Discarded pressed;
- on a dashed one, one with a note kept off the PDF and put back;
- the note a card shows (`noteSummary`): its words line by line, the
  blocks under it at any depth, its threads, and nothing for a bare one;
- a bare one deleted as one tree edit and out of the queue. Its undo puts
  it back in its place, lost, at the top's end when its parent has gone,
  and does nothing when it is back already.
- one with a note kept with its quote, colour, note and children, its
  position its page alone. It is out of the page's highlights and the
  queue, and the rest of the tree keeps its identity.
- that one's undo putting the lost position back, and nothing over a
  later change;
- after Discard, the card as it was, Discarded, and the deleted one kept
  in the walk where it stood;
- the deleted one lost and to check again when it is back, and out of the
  walk once it is placed elsewhere;
- one kept as a note leaving the walk when someone deletes it;
- `placeBox`, `blockPlace` and `insertBlockAt`.

The other web app unit tests:

- `logseqPdfModel.test.mjs`: `placedToCheck`, `roughMatchPercent`,
  `acceptedPosition` (`doc_id` going too), `placeToReview`, `closestPlace`
  (read defensively), `closestPlacement` and `reviewUndone` (nothing taken
  back over a later change);
- `tasks.test.mjs`: `relocationSummary` and the superseded run's silence
  (`relocationSuperseded`);
- `settings.test.mjs`: the two thresholds' defaults and clamping;
- `libraryUtils.test.mjs`: `attachmentFile`.

The browser suite runs a step by part of its name
(`npm run e2e -- --only "<part of the name>"`). The steps are in
`pdf.mjs`, save the last, which is in `files.mjs`.

**"replacing the PDF moves a highlight to its text"**:

- the job's result, and its default thresholds in the params;
- the hollow look on a light and a flipped page and in the notes, a
  comment leaving it hollow;
- attach mode and move mode ending each other;
- two renumbered citations matched roughly, with their stored `match`,
  respelled quote and `prev`;
- the dashed outline on a light and a flipped page, with no title;
- the rows' dashed dots with the percentage, and no Accept on an exact
  match;
- the review card on hover: its header, the before picture with the old
  rectangles over it, the struck and marked citation and "2 of 3" (the
  hollow one first);
- its steps (Accept, Move…, Discard) on one line, and Previous, the count
  and Next on their own under them;
- the card staying while the pointer is in it, and closing when it
  leaves;
- Next: the notes on the next one's block, the focus in the card;
- Accept on the card, leaving the card's box, header, loaded picture,
  comparison, count and every button's box the same within a pixel;
- Accept then reading "Accepted", pressed, its title saying a press undoes
  it. Move… is still enabled and Discard is not.
- the card going on to the hollow one, "1 of 3", the notes following;
- Previous round back to it, Accepted. Accepted pressed again dashes the
  highlight again, saved, the card the same and staying.
- Accept and Accepted pressed again within the moment, the card staying;
- Accept for good, on to the hollow one, and Previous back;
- the accepted card staying while the pointer rests on another dashed
  highlight;
- Move… from it and a selection: "Moved", pressed, the header unchanged
  and Accept enabled;
- Ctrl+Z and Ctrl+Y, and Moved pressed again putting the card back on
  "Accepted", the position saved as accepted;
- Next wrapping to the hollow one. Its card reads "Not found in this
  version" and "1 of 3", the accepted one kept in the walk.
- that card's row in the dashed one's places, Accept disabled with its
  title, and a before picture cut from the first file;
- on to the rough one, "2 of 3", and back with Previous;
- Previous round from the first to the accepted one: "3 of 3", Accepted
  pressed;
- the hollow one's card on hover;
- the Document menu's "Review 2 highlights", Escape, and "Accept 1 rough
  match" for the rest, leaving "Review 1 highlight";
- Move… on the hollow one's card and a selection reopening it: "Moved"
  pressed, Accept enabled, the count "1 of 2", no Close;
- Accept then: "All reviewed", the card staying and nothing more written;
- Accepted pressed again, back on Moved. Moved pressed again puts the
  highlight back hollow with its `doc_id`, saved, "1 of 2".
- the move from the row's button, with no translation;
- a second move by an area drag, clearing the quote and putting no picture
  in the chat;
- an account at 100% whose next renumbered citation is left lost with its
  `closest` place. Its card shows "Closest match", the citations compared
  and a before picture of the second version.
- Show drawing one outline per rectangle;
- its row (Accept, Use closest match, Move…, Discard) with Accept
  disabled;
- Use closest match placing it solid there with the candidate's rectangles
  and quote. The position is free of `lost`, `closest`, `doc_id`,
  `approx`, `match` and `prev`.
- the card then the same and following it: Use closest match reading
  "Placed", pressed, Discard disabled, Accept enabled and the count as it
  was.

**"a highlight still dashed at the next replacement is matched from the
version it was made on"**:

- two highlights dashed on v2;
- v3 printing v1's citation a line lower, which draws it solid with v1's
  quote;
- v1's sentence gone while v3 has one like v2's guess: hollow, no `prev`,
  naming v1, with v1's quote. The outline is at v1's place on v3's page.
- its card: "Not found in this version", v1's quote and a before picture
  of v1.

**"a hollow highlight's card leaves nothing on screen"**:

- nothing of the card on screen, its picture included, while its
  highlight is scrolled out of sight with the pointer in the card;
- that from the scroller, and from the wheel with the card opened from the
  Document menu;
- the card on its highlight again, picture and all, when it scrolls back;
- Discard on the bare one, its title saying it deletes. The card stays the
  same where it stood within a pixel: Discard reading "Discarded",
  pressed, Move… and Accept disabled.
- then on to the one with a note, the highlight gone from the page and the
  store;
- Previous back to it, Discarded. Discarded pressed again brings it back
  hollow with its file and quote, saved, the card staying.
- Next to the one with a note, its note on the card, and Discard, its
  title saying it keeps it. The card still shows the note, then goes on to
  the bare one.
- the row and its note staying, the position its page alone, the quote
  kept;
- Discarded pressed again, hollow again, then a second Discard;
- Next coming back to it, Discarded, and "Review 1 highlight" in the
  Document menu.

**"a review card shows its highlight's note"**:

- a dashed one's note and "2 notes under it · 1 comment" on hover, the
  notes panel staying where it was;
- Next to a bare one whose row was out of the notes' sight: the notes on
  its block, marked, the focus in the card, no note shown;
- Next to a hollow one: its row's buttons in the dashed one's places
  within a pixel, Accept disabled with its title;
- Move… and a selection: Moved, Accept enabled with its title, the card
  the same;
- Accept going on to the noted one, round. The notes are on its block and
  the focus on its Accept, with nothing more written.
- Previous back. Accepted pressed again goes back on Moved, the card
  staying, and Moved pressed again makes it hollow, Accept disabled again.
- Discard on the bare dashed one, its title saying it deletes, the card
  the same;
- Discarded pressed again within the moment, keeping the card;
- Discard again, deleting it and going on to the hollow one. Previous and
  Discarded pressed again bring it back dashed, saved.
- Discard on the noted dashed one, its title saying it keeps it. The card
  still shows its note, then goes on to the bare one.
- that one's position its page alone and its note kept. Previous and
  Discarded pressed again bring it back dashed.

**"on a short window a review card's note gives way"**:

- at 1280x600, a dashed card with an eight-line Before picture, a long
  changed quote and a twelve-line note. It keeps three lines of its
  comparison and one of its note.
- a short comparison shown whole, no taller;
- at 1280x720, the comparison at its full height, the note giving the
  room. The card does not scroll, and Previous / Next are in it.
- a hollow one moved on its card without Accept: the Document menu reads
  "Review 3 highlights" and opens it Moved with Accept enabled. After
  Accept it reads "Review 2 highlights".

**"a hollow highlight's card follows Show to a far page"**:

- Show on a closest match six pages on, keeping the card in sight beside
  the outline without a scroll;
- Next to a highlight lost from a page the new version dropped. Its card
  stands in the viewer and says so, "2 of 2", with a before picture of
  that page in the first file.
- that card still open past the four seconds' wait;
- Next going round to the first, and the Document menu's "Review 2
  highlights";
- Use closest match by Enter, and back;
- on a 360 px phone in English and Chinese, the lost card's steps on one
  line, and Previous, the count and Next on their own.

**"the highlight follows its text"** (`files.mjs`): the Document menu's
Replace the PDF…, the summary in the status pill, the highlight placed
dashed with its note, a second tab following.
