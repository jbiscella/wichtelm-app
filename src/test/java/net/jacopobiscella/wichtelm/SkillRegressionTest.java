package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.strategy.BuiltinCatalog;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard for the bundled claude.ai authoring skill
 * ({@code skills/wichtelm-strategy-author/}). The skill is documentation, but it
 * documents <em>this codebase</em> — the closed function catalog, the parse rules,
 * and the exact parser diagnostics — so it rots silently when the app changes. These
 * checks fail when the skill drifts from the implementation:
 *
 * <ol>
 *   <li>every bundled example {@code .strat} still parses under the real
 *       {@link StrategyParser} (catches grammar/catalog drift, and — since the parser
 *       rejects unknown functions — proves no example invents a primitive);</li>
 *   <li>every entry in {@link BuiltinCatalog} (functions, market variables,
 *       {@code atr_value}) is documented in the catalog reference (catches a new
 *       built-in the skill forgot to list);</li>
 *   <li>the parser diagnostics the skill quotes verbatim still exist in
 *       {@code StrategyParser} source (catches a message rename desyncing the docs);</li>
 *   <li>{@code SKILL.md} has valid front matter so it loads as a skill;</li>
 *   <li>gherkin snippets in the skill markdown carry no inline {@code #} comments — the
 *       parser only strips whole-line comments, so an inline comment in a copied
 *       skeleton would fail to parse — and any complete, placeholder-free snippet
 *       parses.</li>
 * </ol>
 */
class SkillRegressionTest {

    private static final Path SKILL_ROOT = Path.of("skills", "wichtelm-strategy-author");
    private static final Path PARSER_SOURCE = Path.of(
            "src", "main", "java", "net", "jacopobiscella", "wichtelm", "strategy", "StrategyParser.java");

    /** Parser diagnostics the skill reference quotes; each must stay verbatim in both source and doc. */
    private static final String[] QUOTED_PARSER_MESSAGES = {
            "duplicate parameter declaration:",
            "Scenario must terminate with",
            "only 'And with stop_loss at' / 'And with take_profit at' may follow Then",
            "stop_loss/take_profit clauses are not allowed on exit Scenarios",
            "atr_value(...) is only valid in stop_loss/take_profit expressions;",
            "Background series timeframe must be strictly higher than the primary timeframe",
            "trade-context variable not allowed in an entry Scenario:",
            "duplicate Scenario name:",
    };

    @Test
    void bundledExampleStrategiesParseCleanly() {
        Path examples = SKILL_ROOT.resolve("examples");
        assertTrue(Files.isDirectory(examples), "skill examples dir missing: " + examples.toAbsolutePath());
        List<Path> strats = listFiles(examples, ".strat");
        assertTrue(strats.size() >= 5, "expected the bundled example strategies, found " + strats.size());
        for (Path strat : strats) {
            String content = read(strat);
            assertDoesNotThrow(() -> StrategyParser.parse(content, strat.toString()),
                    "bundled example must parse: " + strat);
        }
    }

    @Test
    void everyCatalogEntryIsDocumented() {
        String catalog = read(SKILL_ROOT.resolve("reference").resolve("function-catalog.md"));
        List<String> missing = new ArrayList<>();
        for (String fn : BuiltinCatalog.FUNCTIONS.keySet()) {
            if (!catalog.contains(fn)) {
                missing.add("function " + fn);
            }
        }
        for (String var : BuiltinCatalog.MARKET_VARIABLES) {
            if (!catalog.contains(var)) {
                missing.add("market variable " + var);
            }
        }
        if (!catalog.contains("atr_value")) {
            missing.add("atr_value");
        }
        assertTrue(missing.isEmpty(),
                "function-catalog.md is missing catalog entries (add them so the skill stays complete): " + missing);
    }

    @Test
    void quotedParserMessagesStayInSyncWithSource() {
        String source = read(PARSER_SOURCE);
        String doc = read(SKILL_ROOT.resolve("reference").resolve("validation-rules.md"));
        for (String message : QUOTED_PARSER_MESSAGES) {
            assertTrue(source.contains(message),
                    "validation-rules.md quotes a parser message no longer present in StrategyParser.java "
                            + "(message renamed?): \"" + message + "\"");
            assertTrue(doc.contains(message),
                    "expected validation-rules.md to quote the parser message: \"" + message + "\"");
        }
    }

    @Test
    void skillManifestHasValidFrontMatter() {
        List<String> lines = readLines(SKILL_ROOT.resolve("SKILL.md"));
        assertTrue(!lines.isEmpty() && lines.getFirst().strip().equals("---"),
                "SKILL.md must open with a '---' front-matter delimiter");
        int close = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                close = i;
                break;
            }
        }
        assertTrue(close > 1, "SKILL.md front matter must be closed by a second '---'");
        String frontMatter = String.join("\n", lines.subList(1, close));
        assertTrue(frontMatter.contains("name:"), "SKILL.md front matter must declare name:");
        assertTrue(frontMatter.contains("description:"), "SKILL.md front matter must declare description:");
    }

    @Test
    void gherkinSnippetsAreParserSafe() {
        List<String> inlineCommentViolations = new ArrayList<>();
        for (Path md : listFilesRecursive(SKILL_ROOT, ".md")) {
            List<String> lines = readLines(md);
            boolean inGherkin = false;
            StringBuilder block = new StringBuilder();
            for (String line : lines) {
                String trimmed = line.strip();
                if (!inGherkin && trimmed.equals("```gherkin")) {
                    inGherkin = true;
                    block.setLength(0);
                    continue;
                }
                if (inGherkin && trimmed.equals("```")) {
                    inGherkin = false;
                    maybeParseCompleteBlock(md, block.toString());
                    continue;
                }
                if (inGherkin) {
                    block.append(line).append('\n');
                    // The parser skips only whole-line '#' comments; an inline '#'
                    // after content is parsed as part of the clause and breaks parsing.
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && line.contains("#")) {
                        inlineCommentViolations.add(md + " -> " + trimmed);
                    }
                }
            }
        }
        assertTrue(inlineCommentViolations.isEmpty(),
                "gherkin snippets must not use inline '#' comments (move them to their own line): "
                        + inlineCommentViolations);
    }

    /** A gherkin block that is a full, placeholder-free strategy must parse. */
    private static void maybeParseCompleteBlock(Path source, String block) {
        if (block.contains("Feature:") && !block.contains("<")) {
            assertDoesNotThrow(() -> StrategyParser.parse(block, source + " (gherkin block)"),
                    "complete gherkin snippet in " + source + " must parse");
        }
    }

    // --- small filesystem helpers -------------------------------------------------

    private static List<Path> listFiles(Path dir, String suffix) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> listFilesRecursive(Path dir, String suffix) {
        assertTrue(Files.isDirectory(dir), "skill dir missing: " + dir.toAbsolutePath());
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        if (!Files.isReadable(p)) {
            fail("expected file is missing or unreadable: " + p.toAbsolutePath());
        }
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path p) {
        return read(p).lines().toList();
    }
}
