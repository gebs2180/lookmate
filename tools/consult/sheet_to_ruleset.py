# /// script
# requires-python = ">=3.10"
# dependencies = ["openpyxl>=3.1"]
# ///
"""Turns a service-finder spreadsheet into the ruleset JSON the consult module loads.

    python tools/consult/sheet_to_ruleset.py docs/research/service-finder-template.xlsx \
        backend/app/src/main/resources/consult/ruleset-seed.json

Options: --credit "Name, Salon" (shown to clients once the partner's own version is loaded; leave it out for our
example answers) and --name "...". Needs openpyxl (pip install openpyxl, or run it with `uv run`).

It reads only the yellow input columns of the Questions, Outcomes, Rules and Tests tabs, found by their headers in
row 4. The worked-out grey columns and the Working tab are ignored: the app checks the rules itself (docs/modules/consult.md).
It doesn't validate beyond the sheet's shape; the backend does that on load and on upload, and refuses a ruleset that
fails. To check a converted seed locally, run SeedRulesetTest (docs/modules/consult.md).
"""

import argparse
import json
import sys
from pathlib import Path

import openpyxl

FORMAT = 1
HEADER_ROW = 4
FIRST_ROW = 5

# Answers the app must never store (CLAUDE.md / service-finder.md: "allergy or sensitivity to colour?" only routes to
# a consultation). The question ID is the sheet's; a renamed question needs adding here.
NOT_STORED = {"reaction"}

KINDS = {"book": "BOOK", "consultation": "CONSULTATION"}

# The template's yellow input cells. Below the Tests table sit summary rows ("Tests passing"), which aren't inputs.
YELLOW = "FFF2CC"


def is_input(cell):
    return cell.fill is not None and cell.fill.fill_type is not None and str(cell.fill.fgColor.rgb).upper().endswith(YELLOW)


def text(value):
    if value is None:
        return None
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    s = str(value).strip()
    return s or None


def number(value, where):
    s = text(value)
    if s is None:
        return None
    try:
        n = float(s)
    except ValueError:
        sys.exit(f"{where}: '{s}' is not a number")
    return int(n) if n.is_integer() else n


def columns(ws, wanted):
    """Maps each wanted header (row 4) to its column number; fails if one is missing."""
    found = {}
    for cell in ws[HEADER_ROW]:
        name = text(cell.value)
        if name in wanted:
            found[name] = cell.column
    missing = [w for w in wanted if w not in found]
    if missing:
        sys.exit(f"{ws.title}: missing header(s) in row {HEADER_ROW}: {', '.join(missing)}")
    return found


def rows(ws, cols):
    """Yields (row number, {header: value}) for rows with at least one input cell filled."""
    for r in range(FIRST_ROW, ws.max_row + 1):
        values = {name: ws.cell(row=r, column=c).value for name, c in cols.items()}
        if any(text(v) is not None for v in values.values()):
            yield r, values


def questions(ws):
    cols = columns(ws, ["Question ID", "Question (client sees)", "Clients pick", "Answer (client sees)",
                        "Help text or photo idea"])
    out = []
    by_id = {}
    for r, v in rows(ws, cols):
        qid = text(v["Question ID"])
        answer = text(v["Answer (client sees)"])
        if qid is None or answer is None:
            sys.exit(f"Questions row {r}: every row needs a Question ID and an Answer")
        q = by_id.get(qid)
        if q is None:
            pick = (text(v["Clients pick"]) or "").lower()
            if pick not in ("one", "several"):
                sys.exit(f"Questions row {r}: 'Clients pick' must be One or Several on the first row of {qid}")
            if text(v["Question (client sees)"]) is None:
                sys.exit(f"Questions row {r}: the first row of {qid} needs the question text")
            q = {"id": qid, "text": text(v["Question (client sees)"]), "multiple": pick == "several",
                 "help": text(v["Help text or photo idea"]), "stored": qid not in NOT_STORED, "answers": []}
            by_id[qid] = q
            out.append(q)
        elif out[-1] is not q:
            sys.exit(f"Questions row {r}: the rows of {qid} must be together")
        q["answers"].append(answer)
    return out


def outcomes(ws):
    cols = columns(ws, ["Outcome ID", "Book or consultation", "Service name on your menu", "Combpatible service",
                        "Time (hours)", "From price ($)", "Booking link for this service", "What to expect (client sees)"])
    out = []
    for r, v in rows(ws, cols):
        kind = KINDS.get((text(v["Book or consultation"]) or "").lower())
        if kind is None:
            sys.exit(f"Outcomes row {r}: 'Book or consultation' must be Book or Consultation")
        link = text(v["Booking link for this service"])
        if link is not None and link.rstrip("/").lower() in ("https:", "http:"):
            link = None  # the template's placeholder: no link yet
        out.append({
            "id": text(v["Outcome ID"]),
            "kind": kind,
            "serviceName": text(v["Service name on your menu"]),
            "serviceType": text(v["Combpatible service"]),
            "hours": number(v["Time (hours)"], f"Outcomes row {r}, Time"),
            "fromPrice": number(v["From price ($)"], f"Outcomes row {r}, From price"),
            "bookingUrl": link,
            "whatToExpect": text(v["What to expect (client sees)"]),
        })
    return out


def condition(value, where):
    s = text(value)
    if s is None:
        return None
    if ": " not in s:
        sys.exit(f"{where}: '{s}' should look like 'question_id: Answer'")
    qid, answer = s.split(": ", 1)
    return {"question": qid.strip(), "answer": answer.strip()}


def rules(ws):
    cols = columns(ws, ["If", "Then outcome", "Why (client sees, optional)", "Notes (only you see)"])
    # The three "and" columns share a header, so take them by position after "If".
    first = cols["If"]
    cond_cols = [first, first + 1, first + 2, first + 3]
    for c in cond_cols[1:]:
        if text(ws.cell(row=HEADER_ROW, column=c).value) != "and":
            sys.exit(f"Rules: expected 'and' headers right after 'If'")
    out = []
    for r in range(FIRST_ROW, ws.max_row + 1):
        conds = [condition(ws.cell(row=r, column=c).value, f"Rules row {r}") for c in cond_cols]
        outcome = text(ws.cell(row=r, column=cols["Then outcome"]).value)
        why = text(ws.cell(row=r, column=cols["Why (client sees, optional)"]).value)
        note = text(ws.cell(row=r, column=cols["Notes (only you see)"]).value)
        if outcome is None:
            if any(conds) or why:
                sys.exit(f"Rules row {r}: a rule needs a 'Then outcome'")
            continue
        out.append({"when": [c for c in conds if c], "outcome": outcome, "why": why, "note": note})
    return out


def tests(ws, question_ids):
    header = {text(c.value): c.column for c in ws[HEADER_ROW] if text(c.value)}
    for name in ("Who (a short description)", "Expected outcome"):
        if name not in header:
            sys.exit(f"Tests: missing header '{name}' in row {HEADER_ROW}")
    answer_cols = {name: c for name, c in header.items() if name in question_ids}
    unknown = [name for name, c in header.items()
               if header["Who (a short description)"] < c < header["Expected outcome"] and name not in question_ids]
    if unknown:
        sys.exit(f"Tests: column header(s) {', '.join(unknown)} aren't question IDs in Questions")
    out = []
    for r in range(FIRST_ROW, ws.max_row + 1):
        if not is_input(ws.cell(row=r, column=header["Who (a short description)"])):
            continue  # past the yellow test rows: the sheet's own summary
        who = text(ws.cell(row=r, column=header["Who (a short description)"]).value)
        expected = text(ws.cell(row=r, column=header["Expected outcome"]).value)
        answers = {}
        for qid, c in answer_cols.items():
            s = text(ws.cell(row=r, column=c).value)
            if s is not None:
                answers[qid] = [a.strip() for a in s.split("; ") if a.strip()]
        if who is None and expected is None and not answers:
            continue
        if who is None or expected is None:
            sys.exit(f"Tests row {r}: a test needs 'Who' and 'Expected outcome'")
        out.append({"name": who, "answers": answers, "expected": expected})
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("sheet", type=Path)
    p.add_argument("out", type=Path)
    p.add_argument("--name", default="Not sure what to book?")
    p.add_argument("--credit", default=None,
                   help="who wrote the rules, shown to clients; only for the partner's own version")
    args = p.parse_args()

    wb = openpyxl.load_workbook(args.sheet, data_only=False)
    qs = questions(wb["Questions"])
    ruleset = {
        "format": FORMAT,
        "name": args.name,
        "credit": args.credit,
        "questions": qs,
        "outcomes": outcomes(wb["Outcomes"]),
        "rules": rules(wb["Rules"]),
        "tests": tests(wb["Tests"], {q["id"] for q in qs}),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(ruleset, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"{args.out}: {len(qs)} questions, {len(ruleset['outcomes'])} outcomes, {len(ruleset['rules'])} rules, "
          f"{len(ruleset['tests'])} tests")


if __name__ == "__main__":
    main()
