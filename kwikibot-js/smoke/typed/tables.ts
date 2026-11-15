import assert from "node:assert/strict";
import { parse, Wikitext } from "kwikibot";
import {
  createTableBuilder,
  createTableCell,
  createTableCellOf,
  NewTableCell,
  Table,
  TableBuilder,
  TableCell,
  TableRow,
} from "kwikibot/wikitext";
import { assertArray, assertNone } from "./support.js";

const PAGE = [
  "{{intro}}",
  '{| class="wikitable sortable"',
  "|+ Volcanoes",
  "! Name !! Height",
  "|-",
  "| [[Etna]] || 3,357 m",
  '|- class="tall"',
  '| style="color:red" | Vesuvius',
  "| 1,281 m",
  "|}",
  "[[Category:Volcanoes]]",
].join("\n");

function only<T>(items: T[]): T {
  assertArray(items);
  assert.equal(items.length, 1);
  const item = items[0];
  assert.ok(item !== undefined);
  return item;
}

function cellAt(table: Table, row: number, column: number): TableCell {
  const cell = table.cell(row, column);
  assert.ok(cell instanceof TableCell);
  return cell;
}

function reading(page: Wikitext): Table {
  const table = only(page.tables());
  assert.ok(table instanceof Table);
  assert.equal(table.attributes, 'class="wikitable sortable"');
  assert.equal(table.attribute("class"), "wikitable sortable");
  assertNone(table.attribute("id"));
  assert.equal(table.indent, 0);
  assert.equal(table.isClosed, true);
  assert.equal(table.columnCount, 2);
  assert.ok(table.markup instanceof Wikitext);
  assert.equal(table.markup.toString(), table.toString());
  assert.ok(PAGE.includes(table.toString()));

  const caption = table.caption;
  assert.ok(caption instanceof TableCell);
  assert.equal(caption.isCaption, true);
  assert.equal(caption.text, "Volcanoes");

  const rows = assertArray(table.rows);
  assert.equal(rows.length, 3);
  const header = table.headerRow;
  assert.ok(header instanceof TableRow);
  assert.equal(header.index, 0);
  assert.equal(header.isHeader, true);
  assert.deepEqual(assertArray(table.headers), ["Name", "Height"]);

  const second = rows[2];
  assert.ok(second instanceof TableRow);
  assert.equal(second.attributes, 'class="tall"');
  assert.equal(second.attribute("class"), "tall");
  assert.equal(second.isHeader, false);
  assert.equal(second.markup.toString(), second.toString());
  assert.equal(assertArray(second.cells).length, 2);

  const styled = cellAt(table, 2, 0);
  assert.equal(styled.isHeader, false);
  assert.equal(styled.isCaption, false);
  assert.equal(styled.attributes, 'style="color:red"');
  assert.equal(styled.attribute("style"), "color:red");
  assert.equal(styled.content.toString(), " Vesuvius");
  assert.equal(styled.text, "Vesuvius");
  assert.equal(styled.rowspan, 1);
  assert.equal(styled.colspan, 1);
  assert.equal(styled.markup.toString(), styled.toString());
  assert.deepEqual(assertArray(styled.tables()), []);
  assert.ok(cellAt(table, 0, 0).isHeader);

  const grid = assertArray(table.grid);
  assert.deepEqual(grid.map((row) => row.map((cell) => cell?.text)), [["Name", "Height"], ["Etna", "3,357 m"], ["Vesuvius", "1,281 m"]]);
  assert.deepEqual(assertArray(table.column("Height")).map((cell) => cell?.text), ["3,357 m", "1,281 m"]);
  assertNone(table.cell(9, 9));
  return table;
}

function editing(table: Table): void {
  const etna = cellAt(table, 1, 0);
  assert.equal(etna.withContent("[[Mount Etna]]").toString(), PAGE.replace("[[Etna]]", "[[Mount Etna]]"));
  assert.equal(etna.withContent("a || b").toString(), PAGE.replace("[[Etna]]", "a &#124;&#124; b"));
  assert.equal(etna.withContentOf(parse("''Etna''")).toString(), PAGE.replace("[[Etna]]", "''Etna''"));
  assert.equal(etna.withContentOf(etna.content).toString(), PAGE);
  assert.equal(etna.withAttributes('title="t"').toString(), PAGE.replace("| [[Etna]]", '| title="t" | [[Etna]]'));
  assert.throws(() => etna.withAttributes("a | b"));
  assert.throws(() => etna.withContent("x\ny"));

  assert.equal(table.withAttributes('class="wikitable"').toString(), PAGE.replace("wikitable sortable", "wikitable"));
  assert.equal(only(table.rows.slice(1, 2)).withAttributes('class="first"').toString(), PAGE.replace("|-\n| [[Etna]]", '|- class="first"\n| [[Etna]]'));

  const added = table.withRow(["Fuji", "3,776 m"]).toString();
  assert.equal(added, PAGE.replace("| 1,281 m", "| 1,281 m\n|-\n| Fuji\n| 3,776 m"));
  const cells: NewTableCell[] = [createTableCell("Fuji", 'scope="row"', true), createTableCell("3,776 m")];
  assert.equal(table.withRowOf(cells).toString(), PAGE.replace("| 1,281 m", '| 1,281 m\n|-\n! scope="row" | Fuji\n| 3,776 m'));
  assert.equal(table.withRowAt(1, ["Fuji", "3,776 m"]).toString(), PAGE.replace("|-\n| [[Etna]]", "|-\n| Fuji\n| 3,776 m\n|-\n| [[Etna]]"));
  assert.equal(table.withRowOfAt(1, cells).toString(), PAGE.replace("|-\n| [[Etna]]", '|-\n! scope="row" | Fuji\n| 3,776 m\n|-\n| [[Etna]]'));
  assert.equal(table.withoutRow(2).toString(), PAGE.replace('\n|- class="tall"\n| style="color:red" | Vesuvius\n| 1,281 m', ""));
  assert.throws(() => table.withoutRow(5));
  assert.equal(table.withColumn(["Country", "Italy", "Italy"]).toString(), PAGE.replace("!! Height", "!! Height !! Country").replace("|| 3,357 m", "|| 3,357 m || Italy").replace("| 1,281 m", "| 1,281 m\n| Italy"));
  const column = [createTableCell("Country", "", true), createTableCellOf(parse("[[Italy]]")), createTableCell("Italy")];
  assert.ok(table.withColumnOf(column).toString().includes("|| 3,357 m || [[Italy]]"));
}

function nesting(): void {
  const outer = only(parse("{|\n| a\n{|\n| b || c\n|}\n|}").tables());
  const nested = only(cellAt(outer, 0, 0).tables());
  assert.equal(nested.toString(), "{|\n| b || c\n|}");
  assert.equal(cellAt(nested, 0, 1).withContent("d").toString(), "{|\n| a\n{|\n| b || d\n|}\n|}");
  const spanned = only(parse('{|\n| rowspan="2" | a || b\n|-\n| c\n|}').tables());
  assert.equal(cellAt(spanned, 1, 0).rowspan, 2);
  assert.equal(cellAt(spanned, 0, 0).colspan, 1);
}

function building(): void {
  const builder = createTableBuilder();
  assert.ok(builder instanceof TableBuilder);
  const text = builder.sortable().caption("Volcanoes").header(["Name", "Height"]).row(["Etna", "3,357 m"]).build();
  assert.equal(text, '{| class="wikitable sortable"\n|+ Volcanoes\n! Name\n! Height\n|-\n| Etna\n| 3,357 m\n|}');

  const cell = createTableCellOf(parse("[[Etna]]"), 'colspan="2"');
  assert.ok(cell instanceof NewTableCell);
  assert.equal(cell.attributes, 'colspan="2"');
  assert.equal(cell.isHeader, false);
  assert.equal(cell.content.toString(), "[[Etna]]");
  assert.equal(cell.toString(), "[[Etna]]");
  const header = createTableCell("x || y", "", true);
  assert.equal(header.isHeader, true);

  const inline = createTableBuilder().plain().sortable(false).attributes('style="width:100%"').inline().captionOf(parse("''c''"));
  const built = inline.header(["A", "B"]).rowOf([cell]).row(["a || b", "c"]).toWikitext();
  assert.ok(built instanceof Wikitext);
  assert.equal(built.toString(), "{| style=\"width:100%\"\n|+ ''c''\n! A !! B\n|-\n| colspan=\"2\" | [[Etna]]\n|-\n| a &#124;&#124; b || c\n|}");
  assert.equal(only(built.tables()).rows.length, 3);
  assert.equal(createTableBuilder().inline(false).build(), '{| class="wikitable"\n|}');
  assert.throws(() => createTableBuilder().row(["a {{!}} b"]).build());
  assert.throws(() => createTableCell("a", "x | y"));
}

const page = parse(PAGE);
editing(reading(page));
nesting();
building();
assert.equal(page.toString(), PAGE);
console.log("tables: tables read, edited where they were found, nested and built");
