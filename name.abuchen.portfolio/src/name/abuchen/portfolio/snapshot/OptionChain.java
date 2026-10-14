package name.abuchen.portfolio.snapshot;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import name.abuchen.portfolio.model.Security;

/**
 * Identifies option contracts that belong to the same chain (underlying and
 * expiry date), e.g. the legs of a spread or a box. PP has no option fields,
 * so the chain is read from an OCC ticker symbol (SPX261218C05000000) or an
 * IBKR-style name (SPX 18DEC26 5000 C).
 */
/* package */ final class OptionChain
{
    private static final Pattern OCC = Pattern.compile("^([A-Z.]{1,6})(\\d{6})([CP])(\\d{8})$"); //$NON-NLS-1$
    private static final Pattern NAME = Pattern.compile("^(\\S+) (\\d{2})([A-Z]{3})(\\d{2}) \\d+(?:\\.\\d+)? [CP]$"); //$NON-NLS-1$

    private static final String MONTHS = "JANFEBMARAPRMAYJUNJULAUGSEPOCTNOVDEC"; //$NON-NLS-1$

    /** IBKR trading classes that are the same underlying as the index. */
    private static final Map<String, String> ROOTS = Map.of("SPXW", "SPX", "NDXP", "NDX", "RUTW", "RUT"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private OptionChain()
    {
    }

    /**
     * Returns a key shared by all options with the same underlying and expiry,
     * or empty if the security is not recognized as an option.
     */
    /* package */ static Optional<String> keyOf(Security security)
    {
        var ticker = security.getTickerSymbol();
        if (ticker != null)
        {
            Matcher m = OCC.matcher(ticker.replaceAll("\\s+", "").toUpperCase(Locale.ROOT)); //$NON-NLS-1$ //$NON-NLS-2$
            if (m.matches())
                return Optional.of(root(m.group(1)) + ' ' + m.group(2));
        }

        var name = security.getName();
        if (name != null)
        {
            Matcher m = NAME.matcher(name.trim().toUpperCase(Locale.ROOT));
            int month = m.matches() ? MONTHS.indexOf(m.group(3)) : -1;
            if (month >= 0 && month % 3 == 0)
                return Optional.of(root(m.group(1)) + ' ' + m.group(4) + String.format("%02d", month / 3 + 1) //$NON-NLS-1$
                                + m.group(2));
        }

        return Optional.empty();
    }

    private static String root(String symbol)
    {
        return ROOTS.getOrDefault(symbol, symbol);
    }
}
