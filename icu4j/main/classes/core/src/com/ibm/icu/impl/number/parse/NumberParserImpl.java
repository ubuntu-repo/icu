// © 2017 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html#License
package com.ibm.icu.impl.number.parse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.ibm.icu.impl.number.AffixPatternProvider;
import com.ibm.icu.impl.number.AffixUtils;
import com.ibm.icu.impl.number.MutablePatternModifier;
import com.ibm.icu.impl.number.PatternStringParser;
import com.ibm.icu.number.NumberFormatter.SignDisplay;
import com.ibm.icu.number.NumberFormatter.UnitWidth;
import com.ibm.icu.text.DecimalFormatSymbols;
import com.ibm.icu.util.Currency;
import com.ibm.icu.util.ULocale;

/**
 * Primary number parsing implementation class.
 *
 * @author sffc
 *
 */
public class NumberParserImpl {
    public static NumberParserImpl createParserFromPattern(String pattern) {
        NumberParserImpl parser = new NumberParserImpl();
        ULocale locale = ULocale.ENGLISH;
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);

        MutablePatternModifier mod = new MutablePatternModifier(false);
        AffixPatternProvider provider = PatternStringParser.parseToPatternInfo(pattern);
        mod.setPatternInfo(provider);
        mod.setPatternAttributes(SignDisplay.AUTO, false);
        mod.setSymbols(symbols,
                Currency.getInstance("USD"),
                UnitWidth.FULL_NAME,
                null);
        int flags = 0;
        if (provider.containsSymbolType(AffixUtils.TYPE_PERCENT)) {
            flags |= ParsedNumber.FLAG_PERCENT;
        }
        if (provider.containsSymbolType(AffixUtils.TYPE_PERMILLE)) {
            flags |= ParsedNumber.FLAG_PERMILLE;
        }
        AffixMatcher.generateFromPatternModifier(mod, flags, parser);

        parser.addMatcher(DecimalMatcher.getInstance(symbols));
        parser.addMatcher(WhitespaceMatcher.getInstance());
        parser.addMatcher(new MinusSignMatcher());
        parser.addMatcher(new ScientificMatcher(symbols));
        parser.addMatcher(new CurrencyMatcher(locale));

        parser.setComparator(new Comparator<ParsedNumber>() {
            @Override
            public int compare(ParsedNumber o1, ParsedNumber o2) {
                return o1.charsConsumed - o2.charsConsumed;
            }
        });
        parser.freeze();
        return parser;
    }

    private final List<NumberParseMatcher> matchers;
    private Comparator<ParsedNumber> comparator;
    private boolean frozen;

    public NumberParserImpl() {
        matchers = new ArrayList<NumberParseMatcher>();
        frozen = false;
    }

    public void addMatcher(NumberParseMatcher matcher) {
        matchers.add(matcher);
    }

    public void setComparator(Comparator<ParsedNumber> comparator) {
        this.comparator = comparator;
    }

    public void freeze() {
        frozen = true;
    }

    public void parse(String input, boolean greedy, ParsedNumber result) {
        assert frozen;
        StringSegment segment = new StringSegment(input);
        if (greedy) {
            parseGreedyRecursive(segment, result);
        } else {
            parseLongestRecursive(segment, result);
        }
        for (NumberParseMatcher matcher : matchers) {
            matcher.postProcess(result);
        }
    }

    private void parseGreedyRecursive(StringSegment segment, ParsedNumber result) {
        // Base Case
        if (segment.length() == 0) {
            return;
        }

        int initialOffset = segment.getOffset();
        for (int i = 0; i < matchers.size(); i++) {
            NumberParseMatcher matcher = matchers.get(i);
            matcher.match(segment, result);
            if (segment.getOffset() != initialOffset) {
                // In a greedy parse, recurse on only the first match.
                parseGreedyRecursive(segment, result);
                // The following line resets the offset so that the StringSegment says the same across the function
                // call boundary. Since we recurse only once, this line is not strictly necessary.
                segment.setOffset(initialOffset);
                return;
            }
        }

        // NOTE: If we get here, the greedy parse completed without consuming the entire string.
    }

    private void parseLongestRecursive(StringSegment segment, ParsedNumber result) {
        // Base Case
        if (segment.length() == 0) {
            return;
        }

        // TODO: Give a nice way for the matcher to reset the ParsedNumber?
        ParsedNumber initial = new ParsedNumber();
        initial.copyFrom(result);
        ParsedNumber candidate = new ParsedNumber();

        int initialOffset = segment.getOffset();
        for (int i = 0; i < matchers.size(); i++) {
            NumberParseMatcher matcher = matchers.get(i);
            // In a non-greedy parse, we attempt all possible matches and pick the best.
            for (int charsToConsume = 1; charsToConsume <= segment.length(); charsToConsume++) {
                candidate.copyFrom(initial);

                // Run the matcher on a segment of the current length.
                segment.setLength(charsToConsume);
                boolean maybeMore = matcher.match(segment, candidate);
                segment.resetLength();

                // If the entire segment was consumed, recurse.
                if (segment.getOffset() - initialOffset == charsToConsume) {
                    parseLongestRecursive(segment, candidate);
                    if (comparator.compare(candidate, result) > 0) {
                        result.copyFrom(candidate);
                    }
                }

                // Since the segment can be re-used, reset the offset.
                // This does not have an effect if the matcher did not consume any chars.
                segment.setOffset(initialOffset);

                // Unless the matcher wants to see the next char, continue to the next matcher.
                if (!maybeMore) {
                    break;
                }
            }
        }
    }

    @Override
    public String toString() {
        return "<NumberParserImpl matchers=" + matchers.toString() + ">";
    }
}
