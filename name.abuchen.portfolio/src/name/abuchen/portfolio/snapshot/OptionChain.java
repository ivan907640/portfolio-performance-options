package name.abuchen.portfolio.snapshot;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import name.abuchen.portfolio.model.Security;

/**
 * Identifies option contracts that belong to the same chain (underlying and
 * expiry date), e.g. the legs of a spread or a box. PP has no option fields,
 * so the contract is read from an OCC ticker symbol (SPX261218C05000000) or an
 * IBKR-style name (SPX 18DEC26 5000 C).
 */
public final class OptionChain
{
    /** An option contract; the expiry is yyMMdd. */
    public record Contract(String underlying, String expiry, char right, BigDecimal strike)
    {
        /** Returns a key shared by all options with the same underlying and expiry. */
        public String chainKey()
        {
            return underlying + ' ' + expiry;
        }
    }

    private static final Pattern OCC = Pattern.compile("^([A-Z.]{1,6})(\\d{6})([CP])(\\d{8})$"); //$NON-NLS-1$
    private static final Pattern NAME = Pattern
                    .compile("^(\\S+) (\\d{2})([A-Z]{3})(\\d{2}) (\\d+(?:\\.\\d+)?) ([CP])$"); //$NON-NLS-1$

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
    public static Optional<String> keyOf(Security security)
    {
        return contractOf(security).map(Contract::chainKey);
    }

    /** Returns the option contract, or empty if the security is not one. */
    public static Optional<Contract> contractOf(Security security)
    {
        var ticker = security.getTickerSymbol();
        if (ticker != null)
        {
            Matcher m = OCC.matcher(ticker.replaceAll("\\s+", "").toUpperCase(Locale.ROOT)); //$NON-NLS-1$ //$NON-NLS-2$
            if (m.matches())
                return Optional.of(new Contract(root(m.group(1)), m.group(2), m.group(3).charAt(0),
                                strike(new BigDecimal(m.group(4)).movePointLeft(3))));
        }

        var name = security.getName();
        if (name != null)
        {
            Matcher m = NAME.matcher(name.trim().toUpperCase(Locale.ROOT));
            int month = m.matches() ? MONTHS.indexOf(m.group(3)) : -1;
            if (month >= 0 && month % 3 == 0)
                return Optional.of(new Contract(root(m.group(1)),
                                m.group(4) + String.format("%02d", month / 3 + 1) + m.group(2), //$NON-NLS-1$
                                m.group(6).charAt(0), strike(new BigDecimal(m.group(5)))));
        }

        return Optional.empty();
    }

    /**
     * Returns true if the positions (contract and signed number of shares)
     * of one chain form box spreads: at every strike the call and the put
     * are held in opposite sizes (a synthetic future), and the synthetic
     * futures add up to zero.
     */
    public static boolean isBox(Map<Contract, Long> positions)
    {
        Map<BigDecimal, long[]> byStrike = new HashMap<>();
        positions.forEach((contract, shares) -> {
            var legs = byStrike.computeIfAbsent(contract.strike(), k -> new long[2]);
            legs[contract.right() == 'C' ? 0 : 1] += shares;
        });

        long net = 0;
        boolean held = false;
        for (long[] legs : byStrike.values())
        {
            if (legs[0] + legs[1] != 0)
                return false;
            held |= legs[0] != 0;
            net += legs[0];
        }
        return held && net == 0;
    }

    /** Returns the strike without trailing zeros, so equal strikes are equal keys. */
    private static BigDecimal strike(BigDecimal value)
    {
        var strike = value.stripTrailingZeros();
        return strike.scale() < 0 ? strike.setScale(0) : strike;
    }

    private static String root(String symbol)
    {
        return ROOTS.getOrDefault(symbol, symbol);
    }
}
